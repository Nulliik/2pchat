package com.example.twopchat.yggdrasil

import kotlinx.coroutines.Dispatchers

// Shared across service instances and modes: teardown of an old service must finish
// before its replacement can enter native code. Packet I/O keeps its own threads.
internal val yggdrasilServiceDispatcher = Dispatchers.IO.limitedParallelism(1)
