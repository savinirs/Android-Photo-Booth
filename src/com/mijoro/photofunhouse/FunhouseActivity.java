package com.mijoro.photofunhouse;

import android.app.Activity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Window;

public class FunhouseActivity extends Activity {
	private GLLayer glView;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        glView = new GLLayer(this);
        getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        setContentView(glView);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.next_filter) {
            glView.nextProgram();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    @Override
    protected void onPause() {
        glView.onPause();
        super.onPause();
    }
    
    @Override
    protected void onResume() {
        glView.onResume();
        super.onResume();
    }
}
