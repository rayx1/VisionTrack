package com.example.personcounter

import android.app.Activity
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.example.personcounter.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding  = ActivitySettingsBinding.inflate(layoutInflater)
        settings = SettingsManager(this)
        setContentView(binding.root)

        // Theme.PersonCounter.Settings has an action bar
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_title)
        }

        loadCurrentValues()
        setupListeners()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // -------------------------------------------------------------------------
    // Slider helpers — slider uses integers 10–95 to avoid float precision bugs
    // -------------------------------------------------------------------------

    /** Convert float threshold (0.0–1.0) → slider integer (10–95). */
    private fun thresholdToSlider(t: Float): Float =
        (t * 100f).coerceIn(10f, 95f).let {
            // Snap to nearest step of 5
            (Math.round(it / 5f) * 5f).toFloat()
        }

    /** Convert slider integer (10–95) → float threshold (0.10–0.95). */
    private fun sliderToThreshold(v: Float): Float = v / 100f

    // -------------------------------------------------------------------------
    // Load existing settings into UI
    // -------------------------------------------------------------------------

    private fun loadCurrentValues() {
        val sliderVal = thresholdToSlider(settings.getThreshold())
        binding.sliderThreshold.value = sliderVal
        binding.tvThresholdValue.text = "%.2f".format(sliderToThreshold(sliderVal))

        when (settings.getActiveCamera()) {
            SettingsManager.CAMERA_FRONT -> binding.rbFront.isChecked = true
            SettingsManager.CAMERA_IP    -> { binding.rbIp.isChecked = true; showIpSection(true) }
            else                         -> binding.rbBack.isChecked = true
        }

        binding.etIpUrl.setText(settings.getIpUrl())
        binding.etIpUser.setText(settings.getIpUser())
        binding.etIpPass.setText(settings.getIpPass())
    }

    // -------------------------------------------------------------------------
    // Listeners
    // -------------------------------------------------------------------------

    private fun setupListeners() {
        binding.sliderThreshold.addOnChangeListener { _, value, _ ->
            binding.tvThresholdValue.text = "%.2f".format(sliderToThreshold(value))
        }

        binding.rgCamera.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            showIpSection(checkedId == R.id.rbIp)
        }

        binding.btnSave.setOnClickListener { saveAndFinish() }
    }

    private fun showIpSection(show: Boolean) {
        binding.layoutIpCamera.visibility = if (show) View.VISIBLE else View.GONE
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    private fun saveAndFinish() {
        settings.setThreshold(sliderToThreshold(binding.sliderThreshold.value))

        val camera = when (binding.rgCamera.checkedRadioButtonId) {
            R.id.rbFront -> SettingsManager.CAMERA_FRONT
            R.id.rbIp    -> SettingsManager.CAMERA_IP
            else         -> SettingsManager.CAMERA_BACK
        }
        settings.setActiveCamera(camera)
        settings.setIpUrl(binding.etIpUrl.text?.toString() ?: "")
        settings.setIpUser(binding.etIpUser.text?.toString() ?: "")
        settings.setIpPass(binding.etIpPass.text?.toString() ?: "")

        setResult(Activity.RESULT_OK)
        finish()
    }
}
