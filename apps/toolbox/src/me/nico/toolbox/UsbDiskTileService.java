package me.nico.toolbox;

import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UsbDiskTileService extends TileService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        worker.execute(() -> {
            boolean on = UsbDisk.isOn();
            main.post(() -> render(on));
        });
    }

    @Override
    public void onClick() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean target = tile.getState() != Tile.STATE_ACTIVE;
        if (!target) {
            showDialog(new android.app.AlertDialog.Builder(this,
                    android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Débrancher la clé Ventoy ?")
                    .setMessage("Démonte-la d'abord sur le PC (Éjecter), sinon ses fichiers "
                            + "risquent d'être abîmés.")
                    .setPositiveButton("C'est démonté", (d, w) -> apply(false))
                    .setNegativeButton("Annuler", null)
                    .create());
            return;
        }
        apply(true);
    }

    private void apply(boolean target) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(Tile.STATE_UNAVAILABLE);
        tile.setSubtitle(target ? "Activation…" : "Arrêt…");
        tile.updateTile();
        worker.execute(() -> {
            Boolean result = UsbDisk.setOn(target);
            main.post(() -> {
                if (result == null) {
                    Toast.makeText(this, "Échec (root refusé ?)", Toast.LENGTH_SHORT).show();
                    render(!target);
                } else {
                    render(result);
                }
            });
        });
    }

    private void render(boolean on) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(on ? "Ventoy branché" : "Désactivée");
        tile.updateTile();
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
