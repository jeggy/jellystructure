package dev.jellystructure.jobs

import dev.jellystructure.model.MediaItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class JobEvent {
    @Serializable @SerialName("started")
    data class Started(val jobId: String, val total: Int) : JobEvent()

    @Serializable @SerialName("progress")
    data class FileProgress(val jobId: String, val file: String, val current: Int, val total: Int) : JobEvent()

    @Serializable @SerialName("file_done")
    data class FileDone(val jobId: String, val file: String, val ok: Boolean, val msg: String? = null) : JobEvent()

    @Serializable @SerialName("item_scanned")
    data class ItemScanned(val jobId: String, val item: MediaItem) : JobEvent()

    @Serializable @SerialName("finished")
    data class Finished(val jobId: String, val succeeded: Int, val failed: Int) : JobEvent()
}
