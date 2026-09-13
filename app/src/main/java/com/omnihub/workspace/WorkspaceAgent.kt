package com.omnihub.workspace

import android.content.Context
import com.omnihub.policy.OmniCapability
import com.omnihub.policy.PermissionEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Executes the small, explicit file-operation protocol emitted by an AI source.
 * All paths are confined to OmniWorkspace; destructive operations are never
 * silently executed by the agent.
 */
class WorkspaceAgent(context: Context) {
    private val workspace = OmniWorkspace(context.applicationContext)

    data class Execution(
        val changed: Boolean,
        val summary: String,
        val files: List<String> = emptyList()
    )

    fun promptFor(userMessage: String): String {
        if (!looksLikeFileTask(userMessage)) return userMessage
        val snapshot = workspace.snapshot()
        return """
$userMessage

You have direct access to the OmniHub workspace through a controlled file tool.
Do the requested work instead of only explaining code.
Workspace root: Omni/projects
Current workspace snapshot:
$snapshot

If files must be changed, finish your response with this exact machine-readable block:
<omni_actions>{"actions":[{"op":"write","path":"projects/example.txt","content":"..."}]}</omni_actions>

Supported operations:
- write: create or replace a UTF-8 text file
- mkdir: create a directory
- move: move a file or directory within the workspace
- delete: NEVER emit delete unless the user explicitly requested deletion; it will require approval
Keep paths relative to the workspace. Do not use .., absolute paths, shell commands, or arbitrary Android paths.
You may include multiple actions. Keep the human explanation short because the actions are executed automatically.
""".trimIndent()
    }

    fun execute(response: String): Execution {
        val start = response.indexOf("<omni_actions>")
        val end = response.indexOf("</omni_actions>")
        if (start < 0 || end <= start) return Execution(false, "No file actions requested.")
        val raw = response.substring(start + "<omni_actions>".length, end).trim()
        val actions = runCatching { JSONObject(raw).optJSONArray("actions") }.getOrNull()
            ?: return Execution(false, "Invalid Omni action payload.")

        val changed = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (i in 0 until actions.length()) {
            val action = actions.optJSONObject(i) ?: continue
            when (action.optString("op").lowercase()) {
                "write" -> {
                    if (!PermissionEngine.evaluate(OmniCapability.WRITE_FILE).allowed) {
                        errors += "write blocked by permission policy"
                        continue
                    }
                    val path = action.optString("path")
                    val content = action.optString("content")
                    if (!validPath(path) || content.length > MAX_FILE_CHARS) {
                        errors += "invalid or oversized write: $path"
                        continue
                    }
                    workspace.writeText(path, content)
                    changed += path
                }
                "mkdir" -> {
                    if (!PermissionEngine.evaluate(OmniCapability.CREATE_DIRECTORY).allowed) {
                        errors += "mkdir blocked by permission policy"
                        continue
                    }
                    val path = action.optString("path")
                    if (!validPath(path)) {
                        errors += "invalid directory: $path"
                        continue
                    }
                    workspace.resolve(path).mkdirs()
                    changed += path
                }
                "move" -> {
                    if (!PermissionEngine.evaluate(OmniCapability.MOVE_FILE).allowed) {
                        errors += "move blocked by permission policy"
                        continue
                    }
                    val from = action.optString("from")
                    val to = action.optString("to")
                    if (!validPath(from) || !validPath(to)) {
                        errors += "invalid move: $from -> $to"
                        continue
                    }
                    val src = workspace.resolve(from)
                    val dst = workspace.resolve(to)
                    dst.parentFile?.mkdirs()
                    if (!src.exists() || !src.renameTo(dst)) {
                        errors += "move failed: $from -> $to"
                    } else changed += to
                }
                "delete" -> {
                    val decision = PermissionEngine.evaluate(OmniCapability.DELETE_FILE)
                    errors += if (decision.requiresUserApproval) {
                        "delete requires user approval: ${action.optString("path")}"
                    } else "delete blocked"
                }
                else -> errors += "unsupported action: ${action.optString("op")}"
            }
        }

        val summary = buildString {
            if (changed.isNotEmpty()) append("Changed ${changed.size} file(s): ${changed.joinToString(", ")}")
            if (errors.isNotEmpty()) {
                if (isNotEmpty()) append(". ")
                append("Skipped: ${errors.joinToString("; ")}")
            }
            if (isEmpty()) append("No actions were executed.")
        }
        return Execution(changed.isNotEmpty(), summary, changed)
    }

    private fun looksLikeFileTask(text: String): Boolean {
        val t = text.lowercase()
        return listOf(
            "create a file", "create files", "write a file", "write files",
            "edit the file", "edit files", "modify the file", "modify files",
            "change the code", "implement this", "build the app", "fix the code",
            "add a class", "add a screen", "create a project", "update the project",
            "save this", "put this in", "make the app"
        ).any { it in t }
    }

    private fun validPath(path: String): Boolean {
        if (path.isBlank() || path.length > 240) return false
        if (path.startsWith("/") || path.contains("\\") || path.split('/').any { it == ".." }) return false
        val resolved = workspace.resolve(path).canonicalFile
        return resolved.path.startsWith(workspace.root().canonicalFile.path + File.separator)
    }

    companion object {
        private const val MAX_FILE_CHARS = 512_000
    }
}

private fun OmniWorkspace.snapshot(): String {
    val root = root()
    val files = listRecursively(root).take(80)
    if (files.isEmpty()) return "(empty)"
    return files.joinToString("\n") { file ->
        val rel = file.relativeTo(root).path
        if (file.isFile && file.length() <= 16_000) {
            val body = runCatching { file.readText().take(4_000) }.getOrDefault("")
            "- $rel\n  ---\n${body.prependIndent("  ")}\n  ---"
        } else {
            "- $rel"
        }
    }
}

private fun listRecursively(root: File): List<File> =
    root.walkTopDown()
        .filter { it != root && !it.path.contains("/.trash/") && !it.path.contains("/.trash") }
        .sortedBy { it.relativeTo(root).path.lowercase() }
        .toList()
