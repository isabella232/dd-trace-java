import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.named;

import akka.NotUsed;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.stream.Attributes;
import akka.stream.BidiShape;
import akka.stream.Inlet;
import akka.stream.Outlet;
import akka.stream.scaladsl.BidiFlow;
import akka.stream.scaladsl.Flow;
import akka.stream.stage.AbstractInHandler;
import akka.stream.stage.AbstractOutHandler;
import akka.stream.stage.GraphStage;
import akka.stream.stage.GraphStageLogic;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class AkkaHttpTestInstrumentation implements Instrumenter {

  @Override
  public AgentBuilder instrument(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(named("akka.http.scaladsl.HttpExt"))
        .transform(
            new AgentBuilder.Transformer.ForAdvice()
                .advice(named("bindAndHandle"), AkkaServerTestAdvice.class.getName()));
  }

  public static class AkkaServerTestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodExit(
        @Advice.Argument(value = 0, readOnly = false)
            Flow<HttpRequest, HttpResponse, NotUsed> handler) {
      final BidiFlow<HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed> testSpanFlow =
          BidiFlow.fromGraph(new TestGraphStage());
      handler = testSpanFlow.reversed().join(handler);
    }
  }

  public static class TestGraphStage
      extends GraphStage<BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest>> {
    private final Inlet<HttpRequest> requestInlet = Inlet.create("Datadog.test.requestIn");
    private final Outlet<HttpRequest> requestOutlet = Outlet.create("Datadog.test.requestOut");
    private final Inlet<HttpResponse> responseInlet = Inlet.create("Datadog.test.responseIn");
    private final Outlet<HttpResponse> responseOutlet = Outlet.create("Datadog.test.responseOut");
    private final BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest> shape =
        BidiShape.of(responseInlet, responseOutlet, requestInlet, requestOutlet);

    @Override
    public GraphStageLogic createLogic(final Attributes inheritedAttributes) throws Exception {
      return new GraphStageLogic(shape) {
        {
          final Queue<TraceScope.Continuation> agentScopes = new LinkedBlockingQueue<>();

          setHandler(
              requestInlet,
              new AbstractInHandler() {
                @Override
                public void onPush() throws Exception {
                  final HttpRequest request = grab(requestInlet);

                  final AgentSpan span =
                      startSpan("TEST_SPAN").setTag(DDTags.RESOURCE_NAME, "ServerEntry");
                  final AgentScope scope = activateSpan(span);
                  scope.setAsyncPropagation(true);
                  if (scope != null) {
                    agentScopes.add(scope.capture());
                  }

                  push(requestOutlet, request);
                }

                @Override
                public void onUpstreamFinish() throws Exception {
                  complete(requestOutlet);
                }

                @Override
                public void onUpstreamFailure(final Throwable ex) throws Exception, Exception {
                  final TraceScope.Continuation continuation = agentScopes.poll();
                  if (continuation != null) {
                    continuation.activate().close();
                  }

                  fail(requestOutlet, ex);
                }
              });

          setHandler(
              requestOutlet,
              new AbstractOutHandler() {
                @Override
                public void onPull() throws Exception {
                  pull(requestInlet);
                }

                @Override
                public void onDownstreamFinish() throws Exception {
                  cancel(requestInlet);
                }
              });

          setHandler(
              responseInlet,
              new AbstractInHandler() {
                @Override
                public void onPush() throws Exception {
                  final HttpResponse response = grab(responseInlet);

                  final TraceScope.Continuation continuation = agentScopes.poll();
                  if (continuation != null) {
                    try (final TraceScope scope = continuation.activate()) {
                      activeSpan().finish();
                    }
                  }

                  push(responseOutlet, response);
                }

                @Override
                public void onUpstreamFinish() throws Exception {
                  completeStage();
                }

                @Override
                public void onUpstreamFailure(final Throwable ex) throws Exception {
                  final TraceScope.Continuation continuation = agentScopes.poll();
                  if (continuation != null) {
                    continuation.activate().close();
                  }

                  fail(responseOutlet, ex);
                }
              });

          setHandler(
              responseOutlet,
              new AbstractOutHandler() {
                @Override
                public void onPull() throws Exception {
                  pull(responseInlet);
                }

                @Override
                public void onDownstreamFinish() throws Exception {
                  cancel(responseInlet);
                }
              });
        }
      };
    }

    @Override
    public BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest> shape() {
      return shape;
    }
  }
}
