package com.rjkyker.shizukuai.broker.llm

/**
 * Pinned on-device models. Every entry is an immutable Hugging Face revision + exact byte size + SHA-256
 * (verified 2026-10 against the HF LFS metadata). Nothing outside this table can be downloaded.
 */
data class ModelSpec(
    val id: String,
    val repo: String,
    val revision: String,
    val file: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    /** RAM headroom (MB) required above Android's low-memory threshold before loading. */
    val minFreeRamMb: Long,
    val nCtx: Int = 2048,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/$revision/$file"
    val sizeMb: Long get() = sizeBytes / (1024 * 1024)
}

object ModelCatalog {
    const val STORAGE_MARGIN_BYTES = 750L * 1024 * 1024

    val QWEN25_05B_Q4KM = ModelSpec(
        id = "qwen2.5-0.5b-instruct-q4_k_m",
        repo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        revision = "9217f5db79a29953eb74d5343926648285ec7e67",
        file = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        sizeBytes = 491_400_032L,
        sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        license = "Apache-2.0",
        minFreeRamMb = 900,
    )

    val SMOLLM2_360M_Q4_0 = ModelSpec(
        id = "smollm2-360m-instruct-q4_0",
        repo = "bartowski/SmolLM2-360M-Instruct-GGUF",
        revision = "7be6f65f1db715fe5dc5a4634c0d459b4eed42ec",
        file = "SmolLM2-360M-Instruct-Q4_0.gguf",
        sizeBytes = 229_733_280L,
        sha256 = "c3608933eb6e5763b87f769bda40c204dc158333668c7af214644fe39da58627",
        license = "Apache-2.0",
        minFreeRamMb = 450,
    )

    /** Router order: best quality first, smallest last. */
    val ALL: List<ModelSpec> = listOf(QWEN25_05B_Q4KM, SMOLLM2_360M_Q4_0)

    fun byId(id: String?): ModelSpec? = ALL.firstOrNull { it.id == id }

    fun requiredStorageBytes(spec: ModelSpec): Long = spec.sizeBytes + STORAGE_MARGIN_BYTES
}
