package com.perrinz.deepjarlist

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import java.io.*
import java.security.MessageDigest
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.math.min

// Constants
private val ZIP_MAGIC = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte())
private const val MAX_FILE_SIZE = 128 * 1024 * 1024L // 128MB
private const val BUFFER_SIZE = 4096
private const val MANIFEST_FILENAME = "manifest.mf"
private const val XML_EXTENSION = "xml"
private const val PADDING_BASE = "                                    " // 36 spaces for max depth
private const val FILE_TOO_LARGE_MESSAGE = " = [ Skipping file -- too large. ]"

class DeepJarList : CliktCommand(name = "DeepJarList", help = "Display contents of nested JAR/ZIP files") {
    private val showManifest by option("-m", "--manifest", help = "Show contents of manifest files").flag()
    private val showLineNos by option("-l", "--line-numbers", help = "Show line numbers for displayed files").flag()
    private val showXml by option("-x", "--xml", help = "Show contents of XML files").flag()
    private val showFileSize by option("-z", "--size", help = "Show file size for each file").flag()
    private val showFileHash by option("-5", "--md5", help = "Show MD5 hash for each file").flag()
    private val extensionList by option("-e", "--extensions", help = "Show contents of files with given comma-delimited extensions").split(",")
    private val filterPattern by option("-f", "--filter", help = "Only display file names matching regex")
    private val jarFiles by argument("JAR_FILES", help = "JAR files to analyze").multiple(required = true)

    private val extensions = mutableSetOf<String>()
    private var pattern: Regex? = null

    override fun run() {
        if (showXml) extensions.add(XML_EXTENSION)
        extensionList?.forEach { ext -> 
            extensions.addAll(ext.lowercase().split(",").filter { it.isNotEmpty() })
        }
        pattern = filterPattern?.toRegex()

        jarFiles.forEach { jarFile ->
            val file = File(jarFile)
            if (!file.exists()) {
                println("warning: file ${file.path} does not exist, skipping")
            } else {
                ZipInputStream(FileInputStream(file)).use { zipInputStream ->
                    println("${file.name} = [")
                    listEntries(zipInputStream, 1, extensions, showManifest, showLineNos, pattern, showFileSize, showFileHash)
                    println("]")
                }
            }
        }
    }
}

// Utility functions
private fun readMagicBytes(inputStream: InputStream): ByteArray {
    val magic = ByteArray(ZIP_MAGIC.size)
    inputStream.read(magic)
    return magic
}

private fun isZipFile(magic: ByteArray): Boolean = magic.contentEquals(ZIP_MAGIC)

private fun generatePadding(level: Int): String = PADDING_BASE.substring(0, level * 4)

private fun getFileExtension(filename: String): String? {
    val lastDotPos = filename.lastIndexOf(".")
    return if (lastDotPos > 0) filename.lowercase().substring(lastDotPos + 1) else null
}

private fun shouldShowFileContent(zipEntry: ZipEntry, extensions: Set<String>, showManifest: Boolean): Boolean {
    val extension = getFileExtension(zipEntry.name)
    return (extension != null && extensions.contains(extension)) ||
            (showManifest && zipEntry.name.lowercase().endsWith(MANIFEST_FILENAME))
}

fun main(args: Array<String>) = DeepJarList().main(args)


private fun listEntries(
    zipInputStream: ZipInputStream, 
    level: Int, 
    extensions: Set<String>, 
    showManifest: Boolean, 
    showLineNos: Boolean, 
    pattern: Regex?, 
    showFileSize: Boolean, 
    showFileHash: Boolean
) {
    val padding = generatePadding(level)
    var excludedByPattern = 0
    while (true) {
        val zipEntry = zipInputStream.nextEntry ?: break
        if (zipEntry.isDirectory) {
            if (pattern?.matches(zipEntry.name) != false) {
                println("$padding${zipEntry.name}")
            }
            continue
        }
        val magicBytes = readMagicBytes(zipInputStream)
        val isJar = isZipFile(magicBytes)
        val showFile = shouldShowFileContent(zipEntry, extensions, showManifest)
        
        if (isJar || showFile) {
            print("$padding${zipEntry.name}")
            if (zipEntry.size > MAX_FILE_SIZE) {
                println(FILE_TOO_LARGE_MESSAGE)
            } else {
                val fileBytes = readFileBytes(zipEntry, magicBytes, zipInputStream)
                println(fileInfo(fileBytes, showFileSize, showFileHash) + " = [")
                val bais = ByteArrayInputStream(fileBytes)
                if (isJar) {
                    ZipInputStream(bais).use { nestedZip ->
                        listEntries(nestedZip, level + 1, extensions, showManifest, showLineNos, pattern, showFileSize, showFileHash)
                    }
                } else { // showFile
                    if (showLineNos) {
                        LineNumberReader(InputStreamReader(bais)).use { lnr ->
                            var line: String?
                            while ((lnr.readLine().also { line = it }) != null) {
                                println(
                                    padding + "    ".substring(
                                        min(
                                            lnr.lineNumber.toString().length,
                                            4
                                        )
                                    ) + lnr.lineNumber + " " + line
                                )
                            }
                        }
                    } else {
                        BufferedReader(InputStreamReader(bais)).use { br ->
                            var line: String?
                            while ((br.readLine().also { line = it }) != null) println("$padding    $line")
                        }
                    }
                }
            }
            println("$padding]")
        } else {
            if (pattern == null) {
                println("$padding${zipEntry.name}${fileInfo(zipEntry, magicBytes, zipInputStream, showFileSize, showFileHash)}")
            } else {
                if (pattern.matches(zipEntry.name)) {
                    println("$padding${zipEntry.name}${fileInfo(zipEntry, magicBytes, zipInputStream, showFileSize, showFileHash)}")
                } else {
                    excludedByPattern++
                }
            }
        }
        zipInputStream.closeEntry()
    }
    if (excludedByPattern > 0) {
        println("$padding($excludedByPattern files excluded by filter)")
    }
}

private fun readFileBytes(
    zipEntry: ZipEntry,
    magicBytes: ByteArray,
    zipInputStream: InputStream
): ByteArray {
    val size = zipEntry.size.toInt()
    val baos =
        ByteArrayOutputStream(if (size > 0) size else 65536) // size is sometimes -1, use arbitrary initial array size
    baos.write(magicBytes)
    val fileData = ByteArray(BUFFER_SIZE)
    while (true) {
        val read = zipInputStream.read(fileData, 0, BUFFER_SIZE)
        if (read < 0) break
        baos.write(fileData, 0, read)
    }
    return baos.toByteArray()
}

private fun fileInfo(
    zipEntry: ZipEntry,
    magicBytes: ByteArray,
    zipInputStream: InputStream,
    showFileSize: Boolean,
    showFileHash: Boolean
): String {
    if (!showFileSize && !showFileHash) return ""
    return fileInfo(readFileBytes(zipEntry, magicBytes, zipInputStream), showFileSize, showFileHash)
}

private fun fileInfo(fileBytes: ByteArray, showFileSize: Boolean, showFileHash: Boolean): String {
    if (!showFileSize && !showFileHash) return ""
    
    return buildString {
        if (showFileSize) append("  (${fileBytes.size} bytes)")
        if (showFileHash) {
            append("  ")
            try {
                val md = MessageDigest.getInstance("MD5")
                val hash = md.digest(fileBytes)
                for (aHash in hash) {
                    val c = (aHash.toUByte().toInt())
                    append(c.toString(16).padStart(2, '0'))
                }
            } catch (e: Exception) {
                append("[?]")
                return@buildString
            }
        }
    }
}
