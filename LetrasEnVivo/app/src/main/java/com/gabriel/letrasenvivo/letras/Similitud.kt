package com.gabriel.letrasenvivo.letras

import kotlin.math.max
import kotlin.math.min

/** Medidas de parecido entre textos ya normalizados. */
object Similitud {

    /** Jaro-Winkler clásico, 0..1. */
    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val ventana = max(0, max(a.length, b.length) / 2 - 1)
        val coincideA = BooleanArray(a.length)
        val coincideB = BooleanArray(b.length)
        var coincidencias = 0
        for (i in a.indices) {
            val desde = max(0, i - ventana)
            val hasta = min(b.length - 1, i + ventana)
            for (j in desde..hasta) {
                if (!coincideB[j] && a[i] == b[j]) {
                    coincideA[i] = true
                    coincideB[j] = true
                    coincidencias++
                    break
                }
            }
        }
        if (coincidencias == 0) return 0.0
        var transposiciones = 0
        var k = 0
        for (i in a.indices) {
            if (!coincideA[i]) continue
            while (!coincideB[k]) k++
            if (a[i] != b[k]) transposiciones++
            k++
        }
        val m = coincidencias.toDouble()
        val jaro = (m / a.length + m / b.length + (m - transposiciones / 2.0) / m) / 3.0
        var prefijo = 0
        while (prefijo < min(4, min(a.length, b.length)) && a[prefijo] == b[prefijo]) prefijo++
        return jaro + prefijo * 0.1 * (1 - jaro)
    }

    /** Jaccard sobre conjuntos de palabras, 0..1. */
    fun jaccard(a: Collection<String>, b: Collection<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val sa = a.toSet()
        val sb = b.toSet()
        val interseccion = sa.count { it in sb }
        val union = sa.size + sb.size - interseccion
        return interseccion.toDouble() / union
    }

    /** Parecido combinado entre dos textos crudos (se normalizan dentro). */
    fun similitud(textoA: String, textoB: String): Double {
        val na = NormalizadorTexto.normalizar(textoA)
        val nb = NormalizadorTexto.normalizar(textoB)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        return similitudNormalizada(na, nb)
    }

    /** Igual que [similitud] pero con textos ya normalizados (evita repetir trabajo en bucles). */
    fun similitudNormalizada(na: String, nb: String): Double {
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val jw = jaroWinkler(na, nb)
        val jc = jaccard(na.split(' '), nb.split(' '))
        return 0.5 * jw + 0.5 * jc
    }
}
