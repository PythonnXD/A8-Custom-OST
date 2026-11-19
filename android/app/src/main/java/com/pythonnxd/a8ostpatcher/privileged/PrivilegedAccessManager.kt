package com.pythonnxd.a8ostpatcher.privileged

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.pythonnxd.a8ostpatcher.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class GameAccessMode(val id: String, val label: String) {
    STANDARD("standard", "All files"),
    SHIZUKU("shizuku", "Shizuku"),
    ROOT("root", "Root (su)");

    val isPrivileged: Boolean
        get() = this == SHIZUKU || this == ROOT

    companion object {
        fun fromId(raw: String?): GameAccessMode =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: STANDARD
    }
}

enum class ShizukuStatus {
    NOT_INSTALLED,
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    READY,
}

enum class RootStatus {
    UNKNOWN,
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
}

data class RemoteFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

interface PrivilegedFileBackend {
    val mode: GameAccessMode
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun isFile(path: String): Boolean
    fun listEntries(dirPath: String): List<RemoteFileEntry>
    fun mkdirs(path: String): Boolean
    fun deletePath(path: String): Boolean
    fun testWrite(dirPath: String): Boolean
    fun copyFromRemote(remotePath: String, localDst: File): Boolean
    fun copyToRemote(localSrc: File, remotePath: String): Boolean
}

class PrivilegedAccessManager(private val appCtx: Context) {

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_SETUP_URL = "https://shizuku.rikka.app/"
        private const val SHIZUKU_REQ_CODE = 8808

        fun isRestrictedAndroidDataPath(path: String): Boolean {
            val norm = path.replace('\\', '/').lowercase()
            return norm.contains("/android/data/") ||
                norm.endsWith("/android/data") ||
                norm.contains("/android/obb/") ||
                norm.endsWith("/android/obb")
        }

        internal fun isAllowedStoragePath(path: String): Boolean {
            val norm = path.replace('\\', '/')
            return norm.startsWith("/storage/") ||
                norm.startsWith("/sdcard/") ||
                norm.startsWith("/data/media/")
        }

        internal fun shellQuote(raw: String): String =
            "'" + raw.replace("'", "'\\''") + "'"

        internal fun parseEntryLine(line: String): RemoteFileEntry? {
            val firstSep = line.indexOf('|')
            val lastSep = line.lastIndexOf('|')
            if (firstSep <= 0 || lastSep <= firstSep) return null
            val kind = line.substring(0, firstSep)
            val name = line.substring(firstSep + 1, lastSep)
            val size = line.substring(lastSep + 1).trim().toLongOrNull() ?: 0L
            if (name.isEmpty()) return null
            return RemoteFileEntry(
                name = name,
                isDirectory = kind == "D",
                sizeBytes = size,
            )
        }
    }

    private val _shizukuStatus = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val shizukuStatus: StateFlow<ShizukuStatus> = _shizukuStatus.asStateFlow()

    private val _shizukuUid = MutableStateFlow(-1)
    val shizukuUid: StateFlow<Int> = _shizukuUid.asStateFlow()

    private val _rootStatus = MutableStateFlow(RootStatus.UNKNOWN)
    val rootStatus: StateFlow<RootStatus> = _rootStatus.asStateFlow()

    private val serviceRef = AtomicReference<IPrivilegedFileService?>(null)
    private val bindLock = Any()
    @Volatile
    private var pendingLatch: CountDownLatch? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, PrivilegedFileService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_fs")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val userServiceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = if (binder != null && binder.pingBinder()) {
                IPrivilegedFileService.Stub.asInterface(binder)
            } else null
            serviceRef.set(svc)
            pendingLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceRef.set(null)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshShizukuStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        serviceRef.set(null)
        refreshShizukuStatus()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQ_CODE) {
            refreshShizukuStatus()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                onPermissionGrantedCallback?.invoke()
            }
        }
    }

    @Volatile
    var onPermissionGrantedCallback: (() -> Unit)? = null

    init {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {}
        refreshShizukuStatus()
    }

    fun isShizukuAppInstalled(): Boolean {
        return try {
            appCtx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getShizukuLaunchIntent(): Intent? {
        return try {
            appCtx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        } catch (_: Exception) {
            null
        }
    }

    fun refreshShizukuStatus(): ShizukuStatus {
        val status = try {
            val alive = Shizuku.pingBinder()
            if (!alive) {
                _shizukuUid.value = -1
                if (isShizukuAppInstalled()) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
            } else {
                _shizukuUid.value = try { Shizuku.getUid() } catch (_: Throwable) { -1 }
                val granted = if (Shizuku.isPreV11()) {
                    false
                } else {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }
                if (granted) ShizukuStatus.READY else ShizukuStatus.PERMISSION_REQUIRED
            }
        } catch (_: Throwable) {
            _shizukuUid.value = -1
            if (isShizukuAppInstalled()) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
        }
        _shizukuStatus.value = status
        return status
    }

    fun requestShizukuPermission(): Boolean {
        val status = refreshShizukuStatus()
        if (status == ShizukuStatus.READY) return true
        if (status != ShizukuStatus.PERMISSION_REQUIRED) return false
        return try {
            if (!Shizuku.isPreV11()) {
                Shizuku.requestPermission(SHIZUKU_REQ_CODE)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun checkRootAvailable(): Boolean {
        _rootStatus.value = RootStatus.CHECKING
        val ok = try {
            val proc = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val exit = if (proc.waitFor(5, TimeUnit.SECONDS)) proc.exitValue() else {
                proc.destroyForcibly()
                -1
            }
            exit == 0 && out.contains("uid=0")
        } catch (_: Exception) {
            false
        }
        _rootStatus.value = if (ok) RootStatus.AVAILABLE else RootStatus.UNAVAILABLE
        return ok
    }

    fun getBackend(mode: GameAccessMode): PrivilegedFileBackend? {
        return when (mode) {
            GameAccessMode.STANDARD -> null
            GameAccessMode.SHIZUKU -> {
                if (refreshShizukuStatus() == ShizukuStatus.READY) shizukuBackend else null
            }
            GameAccessMode.ROOT -> {
                if (_rootStatus.value == RootStatus.AVAILABLE || checkRootAvailable()) rootBackend else null
            }
        }
    }

    private fun ensureShizukuService(): IPrivilegedFileService? {
        val cur = serviceRef.get()
        if (cur != null && cur.asBinder().isBinderAlive) return cur
        if (refreshShizukuStatus() != ShizukuStatus.READY) return null

        synchronized(bindLock) {
            val again = serviceRef.get()
            if (again != null && again.asBinder().isBinderAlive) return again
            val latch = CountDownLatch(1)
            pendingLatch = latch
            try {
                Shizuku.bindUserService(userServiceArgs, userServiceConn)
            } catch (_: Throwable) {
                pendingLatch = null
                return null
            }
            try {
                latch.await(7, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {}
            pendingLatch = null
            return serviceRef.get()?.takeIf { it.asBinder().isBinderAlive }
        }
    }

    private val shizukuBackend = object : PrivilegedFileBackend {
        override val mode: GameAccessMode = GameAccessMode.SHIZUKU

        override fun exists(path: String): Boolean =
            try { ensureShizukuService()?.exists(path) == true } catch (_: Throwable) { false }

        override fun isDirectory(path: String): Boolean =
            try { ensureShizukuService()?.isDirectory(path) == true } catch (_: Throwable) { false }

        override fun isFile(path: String): Boolean =
            try { ensureShizukuService()?.isFile(path) == true } catch (_: Throwable) { false }

        override fun listEntries(dirPath: String): List<RemoteFileEntry> =
            try {
                ensureShizukuService()?.listEntries(dirPath)?.mapNotNull { parseEntryLine(it) }.orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }

        override fun mkdirs(path: String): Boolean =
            try { ensureShizukuService()?.mkdirs(path) == true } catch (_: Throwable) { false }

        override fun deletePath(path: String): Boolean =
            try { ensureShizukuService()?.deletePath(path) == true } catch (_: Throwable) { false }

        override fun testWrite(dirPath: String): Boolean =
            try { ensureShizukuService()?.testWrite(dirPath) == true } catch (_: Throwable) { false }

        override fun copyFromRemote(remotePath: String, localDst: File): Boolean {
            val pfd = try { ensureShizukuService()?.openRead(remotePath) } catch (_: Throwable) { null } ?: return false
            return try {
                localDst.parentFile?.mkdirs()
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { inp ->
                    FileOutputStream(localDst).use { out ->
                        inp.copyTo(out, bufferSize = 65536)
                    }
                }
                true
            } catch (_: Exception) {
                localDst.delete()
                false
            }
        }

        override fun copyToRemote(localSrc: File, remotePath: String): Boolean {
            if (!localSrc.isFile) return false
            val pfd = try { ensureShizukuService()?.openWrite(remotePath) } catch (_: Throwable) { null } ?: return false
            return try {
                FileInputStream(localSrc).use { inp ->
                    ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                        inp.copyTo(out, bufferSize = 65536)
                        out.flush()
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private val rootBackend = object : PrivilegedFileBackend {
        override val mode: GameAccessMode = GameAccessMode.ROOT

        private fun runSu(cmd: String, timeoutSec: Long = 15): Pair<Int, String> {
            return try {
                val proc = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().use { it.readText() }
                val code = if (proc.waitFor(timeoutSec, TimeUnit.SECONDS)) proc.exitValue() else {
                    proc.destroyForcibly()
                    -1
                }
                code to out
            } catch (_: Exception) {
                -1 to ""
            }
        }

        override fun exists(path: String): Boolean =
            runSu("[ -e ${shellQuote(path)} ]").first == 0

        override fun isDirectory(path: String): Boolean =
            runSu("[ -d ${shellQuote(path)} ]").first == 0

        override fun isFile(path: String): Boolean =
            runSu("[ -f ${shellQuote(path)} ]").first == 0

        override fun listEntries(dirPath: String): List<RemoteFileEntry> {
            val q = shellQuote(dirPath)
            val script = """
                d=$q
                [ -d "${'$'}d" ] || exit 0
                for f in "${'$'}d"/*; do
                  [ -e "${'$'}f" ] || continue
                  n="${'$'}{f##*/}"
                  if [ -d "${'$'}f" ]; then
                    printf 'D|%s|0\n' "${'$'}n"
                  elif [ -f "${'$'}f" ]; then
                    sz=$(stat -c '%s' "${'$'}f" 2>/dev/null || wc -c < "${'$'}f" 2>/dev/null || echo 0)
                    printf 'F|%s|%s\n' "${'$'}n" "${'$'}sz"
                  fi
                done
            """.trimIndent()
            val (code, out) = runSu(script, timeoutSec = 20)
            if (code != 0) return emptyList()
            return out.lineSequence().mapNotNull { parseEntryLine(it) }.toList()
        }

        override fun mkdirs(path: String): Boolean {
            if (!isAllowedStoragePath(path)) return false
            return runSu("mkdir -p ${shellQuote(path)}").first == 0
        }

        override fun deletePath(path: String): Boolean {
            if (!isAllowedStoragePath(path)) return false
            return runSu("rm -f ${shellQuote(path)}").first == 0
        }

        override fun testWrite(dirPath: String): Boolean {
            if (!isAllowedStoragePath(dirPath)) return false
            val probe = "${dirPath.trimEnd('/')}/.__a8_root_probe_${System.currentTimeMillis()}"
            val qDir = shellQuote(dirPath)
            val qProbe = shellQuote(probe)
            return runSu("mkdir -p $qDir && printf ok > $qProbe && rm -f $qProbe").first == 0
        }

        override fun copyFromRemote(remotePath: String, localDst: File): Boolean {
            if (!isAllowedStoragePath(remotePath)) return false
            return try {
                localDst.parentFile?.mkdirs()
                val proc = ProcessBuilder("su", "-c", "cat ${shellQuote(remotePath)}").start()
                proc.inputStream.use { inp ->
                    FileOutputStream(localDst).use { out ->
                        inp.copyTo(out, bufferSize = 65536)
                    }
                }
                val code = if (proc.waitFor(30, TimeUnit.SECONDS)) proc.exitValue() else {
                    proc.destroyForcibly()
                    -1
                }
                if (code == 0 && localDst.isFile) true else {
                    localDst.delete()
                    false
                }
            } catch (_: Exception) {
                localDst.delete()
                false
            }
        }

        override fun copyToRemote(localSrc: File, remotePath: String): Boolean {
            if (!localSrc.isFile || !isAllowedStoragePath(remotePath)) return false
            val parent = File(remotePath).parent ?: return false
            return try {
                val cmd = "mkdir -p ${shellQuote(parent)} && cat > ${shellQuote(remotePath)}"
                val proc = ProcessBuilder("su", "-c", cmd).start()
                FileInputStream(localSrc).use { inp ->
                    proc.outputStream.use { out ->
                        inp.copyTo(out, bufferSize = 65536)
                        out.flush()
                    }
                }
                val code = if (proc.waitFor(45, TimeUnit.SECONDS)) proc.exitValue() else {
                    proc.destroyForcibly()
                    -1
                }
                code == 0
            } catch (_: Exception) {
                false
            }
        }
    }

    fun release() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {}
        try {
            if (serviceRef.getAndSet(null) != null) {
                Shizuku.unbindUserService(userServiceArgs, userServiceConn, true)
            }
        } catch (_: Throwable) {}
    }
}
