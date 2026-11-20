package com.cosmic802.cosmo

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

object StudyPlayer {

    const val DIR = "/sdcard/Music/CosmoStudy"
    private val EXT = setOf("mp3", "m4a", "ogg", "wav", "flac", "aac")

    var tracks by mutableStateOf<List<String>>(emptyList())
        private set
    var index by mutableStateOf(-1)
        private set
    var playing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var scanned by mutableStateOf(false)
        private set

    private var mp: MediaPlayer? = null

    private fun isAudio(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in EXT

    fun refresh() {
        Thread {
            var names: List<String>? = File(DIR).listFiles()?.map { it.name }
            if (names == null) {
                val (code, out) = RootShell.exec("ls -1 '$DIR'")
                names = if (code == 0) out.lines().map { it.trim() } else emptyList()
            }
            val sorted = names
                .filter { it.isNotEmpty() && isAudio(it) }
                .sortedBy { it.lowercase() }
                .map { "$DIR/$it" }
            tracks = sorted
            if (index >= sorted.size) index = -1
            scanned = true
        }.start()
    }

    fun title(path: String): String = File(path).nameWithoutExtension

    fun play(i: Int) {
        if (tracks.isEmpty() || i !in tracks.indices) return
        release()
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(tracks[i])
            p.setOnCompletionListener { next() }
            p.setOnErrorListener { _, _, _ ->
                error = "Playback error"
                playing = false
                true
            }
            p.prepare()
            p.start()
            mp = p
            index = i
            playing = true
            error = null
        } catch (e: Exception) {
            mp?.release()
            mp = null
            playing = false
            index = i
            error = e.message ?: "Cannot play track"
        }
    }

    fun toggle() {
        val p = mp
        if (p == null) {
            if (tracks.isNotEmpty()) play(if (index in tracks.indices) index else 0)
            return
        }
        try {
            if (p.isPlaying) {
                p.pause()
                playing = false
            } else {
                p.start()
                playing = true
            }
        } catch (_: Exception) {
            playing = false
        }
    }

    fun next() {
        if (tracks.isEmpty()) return
        play((index + 1) % tracks.size)
    }

    fun prev() {
        if (tracks.isEmpty()) return
        if (position() > 3000) {
            seekTo(0)
        } else {
            play((if (index <= 0) tracks.size else index) - 1)
        }
    }

    fun position(): Int = try { mp?.currentPosition ?: 0 } catch (_: Exception) { 0 }

    fun duration(): Int = try { mp?.duration ?: 0 } catch (_: Exception) { 0 }

    fun seekTo(ms: Int) {
        try {
            mp?.seekTo(ms)
        } catch (_: Exception) {
        }
    }

    fun release() {
        try {
            mp?.release()
        } catch (_: Exception) {
        }
        mp = null
        playing = false
    }
}

private fun fmt(ms: Int): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

@Composable
fun StudyPlayerScreen(modifier: Modifier = Modifier) {
    var pos by remember { mutableStateOf(0) }
    var dur by remember { mutableStateOf(0) }
    var seek by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(Unit) { StudyPlayer.refresh() }

    LaunchedEffect(StudyPlayer.playing, StudyPlayer.index) {
        do {
            pos = StudyPlayer.position()
            dur = StudyPlayer.duration()
            delay(400)
        } while (StudyPlayer.playing)
    }

    val current = StudyPlayer.tracks.getOrNull(StudyPlayer.index)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "STUDY PLAYER",
            color = CosmoColors.Amber,
            fontSize = 12.sp,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (current != null) StudyPlayer.title(current) else "NO TRACK SELECTED",
            color = CosmoColors.Cyan,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        StudyPlayer.error?.let {
            Text(text = it, color = CosmoColors.Crimson, fontSize = 11.sp)
        }

        Slider(
            value = (seek ?: pos.toFloat()).coerceIn(0f, maxOf(dur, 1).toFloat()),
            onValueChange = { seek = it },
            onValueChangeFinished = {
                seek?.let { StudyPlayer.seekTo(it.toInt()) }
                seek = null
            },
            valueRange = 0f..maxOf(dur, 1).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = CosmoColors.Cyan,
                activeTrackColor = CosmoColors.Cyan,
                inactiveTrackColor = CosmoColors.Dim
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(fmt((seek ?: pos.toFloat()).toInt()), color = CosmoColors.Cyan, fontSize = 11.sp)
            Text(fmt(dur), color = CosmoColors.Cyan, fontSize = 11.sp)
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ChipButton("PREV", CosmoColors.Cyan, Modifier.weight(1f)) { StudyPlayer.prev() }
            ChipButton(
                if (StudyPlayer.playing) "PAUSE" else "PLAY",
                CosmoColors.Amber,
                Modifier.weight(1f)
            ) { StudyPlayer.toggle() }
            ChipButton("NEXT", CosmoColors.Cyan, Modifier.weight(1f)) { StudyPlayer.next() }
            ChipButton("SCAN", CosmoColors.Cyan, Modifier.weight(1f)) { StudyPlayer.refresh() }
        }

        Spacer(Modifier.height(12.dp))

        if (StudyPlayer.tracks.isEmpty()) {
            Text(
                text = if (StudyPlayer.scanned) "NO AUDIO FOUND IN ${StudyPlayer.DIR}" else "SCANNING...",
                color = CosmoColors.Cyan.copy(alpha = 0.6f),
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                itemsIndexed(StudyPlayer.tracks) { i, path ->
                    val active = i == StudyPlayer.index
                    val shape = RoundedCornerShape(10.dp)
                    Text(
                        text = title(path, i),
                        color = if (active) CosmoColors.Amber else CosmoColors.Cyan.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .background(if (active) CosmoColors.Amber.copy(alpha = 0.10f) else CosmoColors.Panel, shape)
                            .border(1.dp, if (active) CosmoColors.Amber else CosmoColors.Dim, shape)
                            .clickable { StudyPlayer.play(i) }
                            .padding(horizontal = 12.dp, vertical = 11.dp)
                    )
                }
            }
        }
    }
}

private fun title(path: String, i: Int): String = "${i + 1}. ${StudyPlayer.title(path)}"
