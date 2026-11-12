package com.example.mynative.androidide

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Git through JGit. Every function blocks - call it from a background thread. */
object GitManager {

    fun isRepo(dir: File) = File(dir, ".git").exists()

    private fun creds(user: String, token: String): CredentialsProvider? = when {
        token.isBlank() -> null
        user.isBlank() -> UsernamePasswordCredentialsProvider(token, "")
        else -> UsernamePasswordCredentialsProvider(user, token)
    }

    fun init(dir: File): String {
        Git.init().setDirectory(dir).call().close()
        val ignore = File(dir, ".gitignore")
        if (!ignore.exists()) {
            ignore.writeText("build/\n.gradle/\nlocal.properties\n.idea/\n*.iml\n.cxx/\n*.apk\n")
        }
        return "Initialized empty Git repository in ${dir.path}/.git\n(.gitignore created)"
    }

    fun clone(url: String, dest: File, user: String, token: String): String {
        Git.cloneRepository()
            .setURI(url)
            .setDirectory(dest)
            .setCredentialsProvider(creds(user, token))
            .call().close()
        return "Cloned $url\ninto ${dest.path}"
    }

    fun status(dir: File): String = Git.open(dir).use { git ->
        val st = git.status().call()
        val sb = StringBuilder("Branch: ${git.repository.branch}\n")
        val remote = git.repository.config.getString("remote", "origin", "url")
        sb.append("Remote: ${remote ?: "(none - use Remote URL)"}\n\n")
        if (st.isClean) { sb.append("Working tree clean."); return@use sb.toString() }
        fun section(title: String, set: Set<String>, tag: String) {
            if (set.isEmpty()) return
            sb.append("$title (${set.size})\n")
            set.sorted().forEach { sb.append("  $tag $it\n") }
            sb.append('\n')
        }
        section("Staged - new", st.added, "A")
        section("Staged - changed", st.changed, "M")
        section("Staged - removed", st.removed, "D")
        section("Modified", st.modified, "M")
        section("Deleted", st.missing, "D")
        section("Untracked", st.untracked, "?")
        section("Conflicts", st.conflicting, "!")
        sb.toString().trimEnd()
    }

    fun diff(dir: File): String = Git.open(dir).use { git ->
        val out = ByteArrayOutputStream()
        git.diff().setOutputStream(out).call()
        var text = out.toString("UTF-8")
        if (text.length > 200_000) text = text.take(200_000) + "\n... (diff truncated)"
        val untracked = git.status().call().untracked
        val tail = if (untracked.isEmpty()) "" else "\n\nUntracked files (not in diff):\n" + untracked.sorted().joinToString("\n") { "  ? $it" }
        (text.ifBlank { "No changes in tracked files." }) + tail
    }

    fun commitAll(dir: File, message: String, name: String, email: String): String = Git.open(dir).use { git ->
        if (git.status().call().isClean) return@use "Nothing to commit - working tree clean."
        git.add().addFilepattern(".").call()
        git.add().setUpdate(true).addFilepattern(".").call()      // also stage deletions
        val c = git.commit().setMessage(message).setAuthor(name, email).setCommitter(name, email).call()
        "Committed ${c.name.take(7)}: ${c.shortMessage}"
    }

    fun log(dir: File, max: Int = 30): String = Git.open(dir).use { git ->
        try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val sb = StringBuilder()
            for (c in git.log().setMaxCount(max).call()) {
                sb.append("${c.name.take(7)}  ${fmt.format(Date(c.commitTime * 1000L))}  ${c.authorIdent.name}\n")
                sb.append("    ${c.shortMessage}\n")
            }
            sb.toString().ifBlank { "No commits yet." }
        } catch (e: org.eclipse.jgit.api.errors.NoHeadException) {
            "No commits yet."
        }
    }

    fun pull(dir: File, user: String, token: String): String = Git.open(dir).use { git ->
        val r = git.pull().setCredentialsProvider(creds(user, token)).call()
        val merge = r.mergeResult?.mergeStatus?.toString() ?: "-"
        "Pull ${if (r.isSuccessful) "successful" else "FAILED"}\nMerge: $merge"
    }

    fun push(dir: File, user: String, token: String): String = Git.open(dir).use { git ->
        val results = git.push().setCredentialsProvider(creds(user, token)).call()
        val sb = StringBuilder("Push finished\n")
        for (res in results) for (u in res.remoteUpdates) sb.append("  ${u.remoteName}: ${u.status}\n")
        sb.toString().trimEnd()
    }

    fun remoteUrl(dir: File): String =
        Git.open(dir).use { it.repository.config.getString("remote", "origin", "url") ?: "" }

    fun setRemote(dir: File, url: String): String = Git.open(dir).use { git ->
        val cfg = git.repository.config
        cfg.setString("remote", "origin", "url", url)
        cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
        cfg.save()
        "origin -> $url"
    }

    /** current branch + all local branch names */
    fun branches(dir: File): Pair<String, List<String>> = Git.open(dir).use { git ->
        val current = git.repository.branch ?: ""
        val names = git.branchList().call().map { it.name.removePrefix("refs/heads/") }.sorted()
        current to names
    }

    fun checkout(dir: File, name: String, create: Boolean): String = Git.open(dir).use { git ->
        git.checkout().setName(name).setCreateBranch(create).call()
        "On branch ${git.repository.branch}"
    }
}
