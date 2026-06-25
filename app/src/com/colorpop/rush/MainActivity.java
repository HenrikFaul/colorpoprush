package com.colorpop.rush;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Single-activity host for Color Pop Rush. Goes edge-to-edge fullscreen and
 * delegates everything (rendering, input, game flow) to {@link GameView}.
 */
public class MainActivity extends Activity {

    private GameView game;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        game = new GameView(this);
        setContentView(game);
        applyImmersive();
    }

    private void applyImmersive() {
        try {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (game != null) {
            game.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (game != null) {
            game.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (game != null) {
            game.destroy();
        }
    }

    @Override
    public void onBackPressed() {
        if (game != null && game.onBack()) {
            return;
        }
        super.onBackPressed();
    }
}
