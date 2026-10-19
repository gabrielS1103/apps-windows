package com.gabriel.letrasenvivo.ajustes

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.gabriel.letrasenvivo.AplicacionLetras
import com.gabriel.letrasenvivo.R
import com.gabriel.letrasenvivo.databinding.ActividadAjustesBinding
import kotlinx.coroutines.launch

class ActividadAjustes : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActividadAjustesBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.contenedor_ajustes, FragmentoAjustes()).commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

class FragmentoAjustes : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.ajustes, rootKey)
        val app = AplicacionLetras.de(requireContext())
        findPreference<Preference>(Ajustes.CLAVE_RESTABLECER)?.setOnPreferenceClickListener {
            app.ajustes.restablecerPosicion()
            Toast.makeText(requireContext(), R.string.concedido, Toast.LENGTH_SHORT).show()
            true
        }
        findPreference<Preference>(Ajustes.CLAVE_BORRAR_CACHE)?.setOnPreferenceClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                app.cache.borrarTodo()
                Toast.makeText(requireContext(), R.string.concedido, Toast.LENGTH_SHORT).show()
            }
            true
        }
    }
}
