package com.gabriel.letrasenvivo.superposicion

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gabriel.letrasenvivo.R
import com.gabriel.letrasenvivo.ajustes.ModoVisualizacion
import com.gabriel.letrasenvivo.databinding.FilaLetraBinding
import com.gabriel.letrasenvivo.letras.modelos.LineaLetra

/**
 * Lista completa de la letra para la vista expandida, con la línea actual resaltada.
 * @param alTocar se llama con el índice de la línea tocada (para sincronizar con lo que suena).
 */
class AdaptadorLineas(private val alTocar: (Int) -> Unit) : RecyclerView.Adapter<AdaptadorLineas.Fila>() {

    var lineas: List<LineaLetra> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    var modo: ModoVisualizacion = ModoVisualizacion.AMBOS
        set(v) { field = v; notifyDataSetChanged() }
    var tamanoSp: Float = 15f
        set(v) { field = v; notifyDataSetChanged() }
    var indiceActual: Int = -1
        set(v) {
            val anterior = field
            field = v
            if (anterior in lineas.indices) notifyItemChanged(anterior)
            if (v in lineas.indices) notifyItemChanged(v)
        }

    class Fila(val b: FilaLetraBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Fila {
        val f = Fila(FilaLetraBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        f.b.root.setOnClickListener { val i = f.bindingAdapterPosition; if (i != RecyclerView.NO_POSITION) alTocar(i) }
        return f
    }

    override fun getItemCount(): Int = lineas.size

    override fun onBindViewHolder(h: Fila, i: Int) {
        val l = lineas[i]
        val actual = i == indiceActual
        val (original, traducida) = TextosDeLinea.para(l, modo, h.b.root.context)
        h.b.filaOriginal.text = original
        h.b.filaOriginal.visibility = if (original == null) View.GONE else View.VISIBLE
        h.b.filaTraducida.text = traducida
        h.b.filaTraducida.visibility = if (traducida == null) View.GONE else View.VISIBLE
        h.b.filaOriginal.textSize = tamanoSp
        h.b.filaTraducida.textSize = tamanoSp - 1
        h.b.filaOriginal.setTypeface(null, if (actual) Typeface.BOLD else Typeface.NORMAL)
        h.b.filaOriginal.alpha = if (actual || indiceActual == -1) 1f else 0.7f
        h.b.filaTraducida.alpha = h.b.filaOriginal.alpha
        h.b.fila.setBackgroundResource(if (actual) R.drawable.fondo_linea_actual else 0)
    }
}
