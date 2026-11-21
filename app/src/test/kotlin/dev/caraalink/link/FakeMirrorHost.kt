package dev.caraalink.link

/** Records every [MirrorHost] callback a channel fires, for assertions. */
class FakeMirrorHost(private val startVideoResult: Boolean = true) : MirrorHost {
    val capturedConfigs = mutableListOf<CaptureConfig>()
    val touches = mutableListOf<Triple<Int, Int, Int>>()
    val keys = mutableListOf<Int>()
    val opened = mutableListOf<MirrorStream>()
    val closed = mutableListOf<MirrorStream>()
    val frameRequests = mutableListOf<MirrorStream>()
    var startVideoCalls = 0

    override fun onCaptureConfig(config: CaptureConfig) {
        capturedConfigs.add(config)
    }

    override fun onStartVideo(): Boolean {
        startVideoCalls++
        return startVideoResult
    }

    val slots = mutableListOf<Int>()

    override fun onTouch(action: Int, x: Int, y: Int, slot: Int) {
        touches.add(Triple(action, x, y))
        slots.add(slot)
    }

    override fun onKey(key: Int) {
        keys.add(key)
    }

    override fun onDataStreamOpen(stream: MirrorStream) {
        opened.add(stream)
    }

    override fun onDataStreamClosed(stream: MirrorStream) {
        closed.add(stream)
    }

    override fun onFrameRequested(stream: MirrorStream) {
        frameRequests.add(stream)
    }
}
