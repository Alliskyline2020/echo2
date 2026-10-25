package dev.echo.app;

import android.service.quicksettings.Tile;
import android.content.Intent;
import android.service.quicksettings.TileService;

/** 快捷磁贴：一键暂停/恢复聆听（开会、隐私场景随手关）。 */
public class VoiceTile extends TileService {

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        Model.Settings settings = Store.get(this).settings();
        settings.pausedManual = !settings.pausedManual;
        Store.get(this).saveSettings(settings);
        // 状态变化即时生效：暂停 → 停采集；恢复 → 拉起采集
        Intent intent = new Intent(this, RecorderService.class);
        intent.setAction(settings.pausedManual ? "pause" : "start");
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable ignored) {
        }
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean listening = ReminderAlarm.shouldListen(this);
        tile.setState(listening ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
