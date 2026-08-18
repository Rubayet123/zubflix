package com.example.zubflix.stremio

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.R

class AddonsMenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_addons_menu)

        val tvTitle = findViewById<View>(R.id.tv_title)
        tvTitle?.let { view ->
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
                val statusBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                val marginParams = v.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                if (marginParams != null) {
                    val baseMargin = (32 * resources.displayMetrics.density).toInt()
                    marginParams.topMargin = baseMargin + statusBarInset
                    v.layoutParams = marginParams
                }
                windowInsets
            }
        }

        val btnLocalScrapers = findViewById<View>(R.id.btn_local_scrapers)
        val btnStremio = findViewById<View>(R.id.btn_stremio_addons)
        val btnCloudStream = findViewById<View>(R.id.btn_cloudstream_addons)

        applyTvFocusAnimation(btnLocalScrapers)
        applyTvFocusAnimation(btnStremio)
        btnCloudStream?.let { applyTvFocusAnimation(it) }

        btnLocalScrapers.setOnClickListener {
            startActivity(Intent(this, com.example.zubflix.bdix.LocalScrapersActivity::class.java))
        }

        btnStremio.setOnClickListener {
            startActivity(Intent(this, AddonsManagerActivity::class.java))
        }

        btnCloudStream?.setOnClickListener {
            startActivity(Intent(this, com.example.zubflix.cloudstream.CloudStreamAddonsActivity::class.java))
        }
    }

    private fun applyTvFocusAnimation(view: View) {
        view.isFocusable = true
        val density = view.resources.displayMetrics.density
        val elevationPx = 4 * density
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate()
                    .scaleX(1.04f)
                    .scaleY(1.04f)
                    .translationZ(elevationPx)
                    .setDuration(150)
                    .start()
            } else {
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationZ(0f)
                    .setDuration(120)
                    .start()
            }
        }
    }
}
