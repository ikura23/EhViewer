package tachiyomi.data.release

import com.hippo.ehviewer.util.AppConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Repository(@SerialName("default_branch") val defaultBranch: String)

@Serializable
data class WorkflowRuns(@SerialName("workflow_runs") val workflowRuns: List<WorkflowRun>)

@Serializable
data class WorkflowRun(
    @SerialName("head_sha") val headSha: String,
    @SerialName("display_title") val title: String,
    @SerialName("id") val id: Long,
)

@Serializable
data class ArtifactsResponse(
    @SerialName("artifacts") val artifacts: List<Artifact> = emptyList(),
)

val List<Artifact>.downloadLinkOrNull: String?
    get() = find { AppConfig.matchVariant(it.name) }?.downloadLink

@Serializable
data class Artifact(
    val name: String,
    @SerialName("archive_download_url")
    val downloadLink: String,
)
