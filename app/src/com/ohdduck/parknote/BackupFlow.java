package com.ohdduck.parknote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 백업 내보내기 · 가져오기 흐름.
 *
 * <p>파일 읽기·쓰기는 워커 스레드에서 한다. 사용자가 저장 위치로 구글 드라이브나
 * 원드라이브를 고르면 SAF 제공자가 네트워크를 타는데, 예전처럼 onActivityResult에서
 * 동기로 부르면 그동안 화면이 통째로 멈춘다(ANR 구간).
 */
class BackupFlow {

    static final int REQ_EXPORT = 20;
    static final int REQ_IMPORT = 21;

    /** 백업 파일은 한 번에 하나만 다루므로 단일 스레드로 충분하다. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BackupFlow() {
    }

    static void showMenu(Activity a) {
        new AlertDialog.Builder(a)
                .setTitle(R.string.backup_title)
                .setMessage(R.string.backup_message)
                .setItems(new String[]{
                        a.getString(R.string.backup_export),
                        a.getString(R.string.backup_import)}, (d, which) -> {
                    if (which == 0) startExport(a);
                    else startImport(a);
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private static void startExport(Activity a) {
        try {
            a.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(Backup.MIME)
                    .putExtra(Intent.EXTRA_TITLE, Backup.suggestedFileName()), REQ_EXPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, R.string.backup_no_saver, Toast.LENGTH_SHORT).show();
        }
    }

    private static void startImport(Activity a) {
        try {
            // 파일 관리자마다 .json을 application/json으로 안 보는 경우가 있어 전체를 연다.
            a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"), REQ_IMPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, R.string.backup_no_picker, Toast.LENGTH_SHORT).show();
        }
    }

    /** 문서 선택기 결과를 처리한다. 이 흐름의 결과였으면 true. */
    static boolean handleResult(Activity a, ScreenHost host, int requestCode,
                                Uri uri) {
        if (requestCode == REQ_EXPORT) {
            export(a, uri);
            return true;
        }
        if (requestCode == REQ_IMPORT) {
            load(a, host, uri);
            return true;
        }
        return false;
    }

    private static void export(Activity a, Uri uri) {
        Context app = a.getApplicationContext();
        Toast.makeText(a, R.string.backup_saving, Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            Integer error = Backup.writeTo(app, uri);
            MAIN.post(() -> {
                if (gone(a)) return;
                Toast.makeText(a, error == null ? R.string.backup_saved : error,
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private static void load(Activity a, ScreenHost host, Uri uri) {
        Context app = a.getApplicationContext();
        Toast.makeText(a, R.string.backup_reading, Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            Backup.Loaded loaded = Backup.readFrom(app, uri);
            MAIN.post(() -> {
                if (gone(a)) return;
                if (!loaded.ok()) {
                    Toast.makeText(a, loaded.errorRes, Toast.LENGTH_LONG).show();
                    return;
                }
                confirmImport(a, host, loaded);
            });
        });
    }

    private static void confirmImport(Activity a, ScreenHost host, Backup.Loaded loaded) {
        new AlertDialog.Builder(a)
                .setTitle(R.string.backup_import)
                .setMessage(a.getString(R.string.backup_import_confirm, summary(a, loaded)))
                .setPositiveButton(R.string.backup_import_action, (d, w) -> {
                    Store.importData(a, loaded.data);
                    host.refresh(true);
                    Toast.makeText(a, R.string.backup_imported, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** "언제 저장한 백업이고 무엇이 몇 개 들었는지" 한 줄. */
    private static String summary(Activity a, Backup.Loaded loaded) {
        String when = loaded.savedAt > 0
                ? Store.formatFull(loaded.savedAt)
                : a.getString(R.string.backup_saved_unknown);
        return a.getString(R.string.backup_summary, when,
                loaded.counts[0], loaded.counts[1], loaded.counts[2], loaded.counts[3]);
    }

    /** 파일 I/O가 끝났을 때 화면이 이미 닫혔으면 다이얼로그를 띄우면 안 된다. */
    private static boolean gone(Activity a) {
        return a.isFinishing() || a.isDestroyed();
    }
}
