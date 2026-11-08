#!/usr/bin/env python3
"""Personnalise une image système GSI (ext4) sans root, via debugfs.

Étapes : copie de l'image de base -> agrandissement -> suppressions -> props -> ajout d'APK
-> fsck -> réduction au minimum. Config : /lab/ulphone/custom/config-a17.json
Usage : customize-gsi.py [config.json]
"""
import datetime
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

DEFAULT_CONFIG = Path("/lab/ulphone/custom/config-a17.json")
SELINUX_SYSTEM_FILE = "u:object_r:system_file:s0"
GROW_MARGIN_BLOCKS = 131072  # 512 Mio en blocs de 4 Kio


def run(cmd, check=True):
    result = subprocess.run(cmd, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} a échoué ({result.returncode}):\n{result.stderr}")
    return result


def debugfs(image, command, write=False):
    args = ["debugfs"] + (["-w"] if write else []) + ["-R", command, str(image)]
    return run(args).stdout


def debugfs_script(image, commands):
    with tempfile.NamedTemporaryFile("w", suffix=".debugfs", delete=False) as f:
        f.write("\n".join(commands) + "\n")
        script = f.name
    out = run(["debugfs", "-w", "-f", script, str(image)])
    Path(script).unlink()
    errors = [line for line in out.stderr.splitlines() if "debugfs" not in line and line.strip()]
    if errors:
        raise RuntimeError("Erreurs debugfs :\n" + "\n".join(errors))


def list_dir(image, path):
    """Retourne [(nom, est_dossier)] d'un dossier de l'image."""
    entries = []
    for line in debugfs(image, f"ls -p {path}").splitlines():
        fields = line.strip().strip("/").split("/")
        if len(fields) < 5 or fields[4] in ("", ".", ".."):
            continue
        mode = int(fields[1], 8)
        entries.append((fields[4], (mode & 0o170000) == 0o040000))
    return entries


def exists(image, path):
    result = run(["debugfs", "-R", f"stat {path}", str(image)], check=False)
    return "File not found" not in result.stderr


def is_dir(image, path):
    return "Type: directory" in debugfs(image, f"stat {path}")


def removal_commands(image, path):
    """Commandes debugfs pour supprimer path : un fichier, ou un dossier récursivement (du bas vers le haut)."""
    if not is_dir(image, path):
        return [f"rm {path}"]
    commands = []
    for name, child_is_dir in list_dir(image, path):
        child = f"{path}/{name}"
        commands += removal_commands(image, child) if child_is_dir else [f"rm {child}"]
    return commands + [f"rmdir {path}"]


def label_commands(path, mode):
    return [
        f"set_inode_field {path} mode 0{mode:o}",
        f"set_inode_field {path} uid 0",
        f"set_inode_field {path} gid 0",
        f'ea_set {path} security.selinux "{SELINUX_SYSTEM_FILE}"',
    ]


def remove_paths(image, paths):
    commands = []
    for path in paths:
        if not exists(image, path):
            print(f"  (absent, ignoré) {path}")
            continue
        commands += removal_commands(image, path)
        print(f"  - {path}")
    if commands:
        debugfs_script(image, commands)


def patch_props(image, props_by_file, workdir):
    stamp = datetime.date.today().isoformat()
    for prop_file, props in props_by_file.items():
        original = debugfs(image, f"cat {prop_file}").splitlines()
        wanted = {k: v.replace("{date}", stamp) for k, v in props.items()}
        # Replace every occurrence (build.prop may define a key twice; the last one wins).
        lines = [
            f"{line.split('=', 1)[0]}={wanted[line.split('=', 1)[0]]}"
            if "=" in line and line.split("=", 1)[0] in wanted else line
            for line in original
        ]
        present = {line.split("=", 1)[0] for line in original if "=" in line}
        lines += [f"{k}={v}" for k, v in wanted.items() if k not in present]
        local = workdir / Path(prop_file).name
        local.write_text("\n".join(lines) + "\n")
        mode = int(debugfs(image, f"stat {prop_file}").split("Mode:")[1].split()[0], 8)
        parent = str(Path(prop_file).parent)
        debugfs_script(image, [f"rm {prop_file}", f"cd {parent}", f"write {local} {Path(prop_file).name}"]
                       + label_commands(prop_file, 0o100000 | mode))
        for key, value in props.items():
            print(f"  {prop_file}: {key}={value.replace('{date}', stamp)}")


def install_apps(image, apps_dir, install_dir):
    apks = sorted(Path(apps_dir).glob("*.apk")) if Path(apps_dir).is_dir() else []
    for apk in apks:
        name = apk.stem
        target_dir = f"{install_dir}/{name}"
        target = f"{target_dir}/{name}.apk"
        if exists(image, target_dir):
            debugfs_script(image, removal_commands(image, target_dir))
        debugfs_script(image, [f"mkdir {target_dir}", *label_commands(target_dir, 0o040755),
                               f"cd {target_dir}", f"write {apk} {name}.apk",
                               *label_commands(target, 0o100644)])
        print(f"  + {target}")


def inode_attrs(image, path):
    """(mode, uid, gid, selinux label) of a file in the image."""
    stat = debugfs(image, f"stat {path}")
    mode = int(stat.split("Mode:")[1].split()[0], 8)
    uid, gid = int(stat.split("User:")[1].split()[0]), int(stat.split("Group:")[1].split()[0])
    label = debugfs(image, f"ea_get {path} security.selinux").split('"')[1].removesuffix("\\000")
    return mode, uid, gid, label


def edit_text_files(image, edits, workdir):
    """Literal replacements in text files, keeping mode/owner/SELinux label:
    {"/system/bin/x.sh": [["old", "new"], ...]}. Every "old" must be present."""
    for path, replacements in edits.items():
        text = debugfs(image, f"cat {path}")
        for old, new in replacements:
            if old not in text:
                raise RuntimeError(f"{path} : motif introuvable : {old!r}")
            text = text.replace(old, new)
        mode, uid, gid, label = inode_attrs(image, path)
        local = workdir / Path(path).name
        local.write_text(text)
        debugfs_script(image, [
            f"rm {path}", f"cd {Path(path).parent}", f"write {local} {Path(path).name}",
            f"set_inode_field {path} mode 0{0o100000 | mode:o}",
            f"set_inode_field {path} uid {uid}", f"set_inode_field {path} gid {gid}",
            f'ea_set {path} security.selinux "{label}"',
        ])
        print(f"  ~ {path} ({len(replacements)} remplacement(s), {mode:o} {label})")


def add_files(image, files):
    """Copy local files into the image: {"/system/dest/path": "local/source"} (mode 0644, system_file)."""
    for dest, src in files.items():
        dest_dir, name = str(Path(dest).parent), Path(dest).name
        commands = [f"rm {dest}"] if exists(image, dest) else []
        commands += [f"cd {dest_dir}", f"write {src} {name}", *label_commands(dest, 0o100644)]
        debugfs_script(image, commands)
        print(f"  + {dest}")


def main():
    config_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_CONFIG
    config = json.loads(config_path.read_text())
    base, output = Path(config["base_image"]), Path(config["output_image"])
    output.parent.mkdir(parents=True, exist_ok=True)

    print(f"Copie {base.name} -> {output}")
    shutil.copyfile(base, output)
    run(["e2fsck", "-fy", str(output)], check=False)
    blocks = int(run(["dumpe2fs", "-h", str(output)]).stdout.split("Block count:")[1].split()[0])
    run(["resize2fs", str(output), str(blocks + GROW_MARGIN_BLOCKS)])

    print("Suppressions :")
    remove_paths(output, config.get("remove", []))
    with tempfile.TemporaryDirectory() as tmp:
        print("Propriétés :")
        patch_props(output, config.get("props", {}), Path(tmp))
        print("Fichiers modifiés :")
        edit_text_files(output, config.get("edit_files", {}), Path(tmp))
    print("Applis ajoutées :")
    install_apps(output, config.get("apps_dir", ""), config.get("apps_install_dir", "/system/product/app"))
    print("Fichiers ajoutés :")
    add_files(output, config.get("add_files", {}))

    fsck = run(["e2fsck", "-fy", str(output)], check=False)
    if fsck.returncode > 1:
        raise RuntimeError(f"e2fsck a trouvé des erreurs non corrigées :\n{fsck.stdout}")
    run(["resize2fs", "-M", str(output)])
    avb = config.get("avb")
    if avb:
        # The base image's AVB hashtree footer is lost when the filesystem is modified; DSU first-stage
        # mount needs it, so re-sign with the same key/rollback index as the original image.
        size = output.stat().st_size
        partition_size = (size + size // 64 + 2**20 + 4095) // 4096 * 4096  # room for hashtree + vbmeta
        run(["avbtool", "add_hashtree_footer", "--image", str(output), "--partition_name", "system",
             "--partition_size", str(partition_size), "--do_not_generate_fec", "--hash_algorithm", "sha256",
             "--algorithm", avb["algorithm"], "--key", avb["key"],
             "--rollback_index", str(avb.get("rollback_index", 0))])
        print(f"Signature AVB : {avb['algorithm']} ({Path(avb['key']).name})")
    print(f"Image prête : {output} ({output.stat().st_size / 2**20:.0f} Mio)")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as err:
        sys.exit(f"ERREUR : {err}")
