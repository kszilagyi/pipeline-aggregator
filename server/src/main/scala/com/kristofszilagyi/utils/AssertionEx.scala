package com.kristofszilagyi.utils

import com.kristofszilagyi.shared.Wart

object AssertionEx {
  @SuppressWarnings(Array(Wart.Throw))
  final def fail(msg: String): Nothing = {
    throw new AssertionError(msg)
  }

  @SuppressWarnings(Array(Wart.Throw))
  final def failEx(msg: String, cause: Throwable): Nothing = {
    throw new AssertionError(msg, cause)
  }
}
