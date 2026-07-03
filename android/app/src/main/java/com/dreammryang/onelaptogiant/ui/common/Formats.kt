package com.dreammryang.onelaptogiant.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

fun formatTime(millis: Long): String = TIME_FMT.format(Instant.ofEpochMilli(millis))
