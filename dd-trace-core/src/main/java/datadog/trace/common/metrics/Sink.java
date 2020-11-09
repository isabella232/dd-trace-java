package datadog.trace.common.metrics;

import datadog.trace.core.serialization.ByteBufferConsumer;

public interface Sink extends ByteBufferConsumer {

  void subscribe(EventListener listener);
}
