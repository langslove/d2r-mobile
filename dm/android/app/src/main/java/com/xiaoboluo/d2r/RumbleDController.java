package com.xiaoboluo.d2r;

// Lifecycle contract only; the D2 implementation owns the only output worker.
interface RumbleDController {
    void resume(); void pause(); void close();
}
