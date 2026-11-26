package com.hippo.ehviewer.updater

import com.hippo.ehviewer.client.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.data.release.ArtifactsResponse
import tachiyomi.data.release.WorkflowRuns

class AppUpdaterVersionTest {
    @Test
    fun ignoresLeadingVPrefixAndStopsRepeatedPromptForSameVersion() {
        assertFalse(isRemoteVersionNewer("v1.14.6", "1.14.6"))
        assertFalse(isRemoteVersionNewer("V1.14.6", "v1.14.6"))
        assertFalse(isRemoteVersionNewer("1.14.6", "1.14.6"))
    }

    @Test
    fun detectsHigherSemanticVersionsAndSnapshotToReleaseUpgrade() {
        assertTrue(isRemoteVersionNewer("v1.14.7", "1.14.6"))
        assertTrue(isRemoteVersionNewer("v1.15.0", "1.14.9"))
        assertTrue(isRemoteVersionNewer("v1.15.0", "1.15.0-SNAPSHOT"))
        assertFalse(isRemoteVersionNewer("1.15.0-SNAPSHOT", "1.15.0"))
        assertFalse(isRemoteVersionNewer("v1.14.5", "1.14.6"))
    }

    @Test
    fun parsesGitHubWorkflowRunsAndArtifactsSchema() {
        val runsJson = """
            {
              "total_count": 1,
              "workflow_runs": [
                {
                  "id": 223344556677,
                  "head_sha": "13e8a68ad99887766",
                  "display_title": "feat: add update tracker"
                }
              ]
            }
        """.trimIndent()
        val runs = json.decodeFromString<WorkflowRuns>(runsJson)
        assertEquals(1, runs.workflowRuns.size)
        assertEquals(223344556677L, runs.workflowRuns[0].id)
        assertEquals("13e8a68ad99887766", runs.workflowRuns[0].headSha)
        assertEquals("feat: add update tracker", runs.workflowRuns[0].title)

        val artifactsJson = """
            {
              "total_count": 1,
              "artifacts": [
                {
                  "name": "ehviewer-release-apk-arm64-v8a",
                  "archive_download_url": "https://api.github.com/repos/ikura23/EhViewer/actions/artifacts/1/zip"
                }
              ]
            }
        """.trimIndent()
        val artifactsResp = json.decodeFromString<ArtifactsResponse>(artifactsJson)
        assertEquals(1, artifactsResp.artifacts.size)
        assertEquals(
            "https://api.github.com/repos/ikura23/EhViewer/actions/artifacts/1/zip",
            artifactsResp.artifacts[0].downloadLink,
        )
    }
}
