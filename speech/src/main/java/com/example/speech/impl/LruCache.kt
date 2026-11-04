package com.example.speech.impl

/**
 * Небольшой LRU-кеш без внешних зависимостей.
 *
 * Зачем: диаризатор привязан к числу кластеров, и каждый экземпляр держит
 * segmentation- и embedding-модель (~27 МБ). Без кеша чередование режимов
 * «авто → 2 → 3 → 2» перезагружает веса на каждом задании. Кеш ограничен
 * по размеру, а вытесненный экземпляр обязательно освобождается через
 * [onEvict] — иначе native-память утечёт.
 *
 * Доступ синхронизирован снаружи (в `SherpaTranscriber` — общим `Mutex`).
 */
internal class LruCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val onEvict: (V) -> Unit = {},
) {
    init {
        require(maxEntries > 0) { "maxEntries должен быть > 0, получено $maxEntries" }
    }

    private val entries = object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
            val evict = size > maxEntries
            if (evict) onEvict(eldest.value)
            return evict
        }
    }

    val size: Int get() = entries.size

    /** Возвращает значение по ключу или создаёт его и кладёт в кеш. */
    fun getOrCreate(key: K, create: () -> V): V {
        entries[key]?.let { return it }
        val value = create()
        entries[key] = value
        return value
    }

    /** Освобождает все значения (в том числе невытесненные) в порядке вставки. */
    fun clear() {
        val snapshot = entries.values.toList()
        entries.clear()
        snapshot.forEach(onEvict)
    }
}
