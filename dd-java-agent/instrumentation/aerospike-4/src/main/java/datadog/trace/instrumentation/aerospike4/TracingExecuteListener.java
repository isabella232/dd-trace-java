package datadog.trace.instrumentation.aerospike4;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Key;
import com.aerospike.client.listener.ExecuteListener;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.context.TraceScope;

public final class TracingExecuteListener extends AbstractTracingListener<ExecuteListener>
    implements ExecuteListener {

  public TracingExecuteListener(final AgentScope clientScope, final ExecuteListener listener) {
    super(clientScope, listener);
  }

  @Override
  public void onSuccess(final Key key, final Object obj) {
    super.onSuccess();

    if (listener != null) {
      try (final TraceScope scope = continuation.activate()) {
        listener.onSuccess(key, obj);
      }
    } else {
      continuation.cancel();
    }
  }

  @Override
  public void onFailure(final AerospikeException cause) {
    super.onFailure(cause);

    if (listener != null) {
      try (final TraceScope scope = continuation.activate()) {
        listener.onFailure(cause);
      }
    } else {
      continuation.cancel();
    }
  }
}