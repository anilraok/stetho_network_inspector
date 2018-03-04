package com.brianegan.flutterstetho;

import com.facebook.stetho.Stetho;
import com.facebook.stetho.inspector.network.DefaultResponseHandler;
import com.facebook.stetho.inspector.network.NetworkEventReporter;
import com.facebook.stetho.inspector.network.NetworkEventReporterImpl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;

/**
 * FlutterStethoPlugin
 */
public class FlutterStethoPlugin implements MethodCallHandler {
    interface QueueItem {
    }

    class ByteQueueItem implements QueueItem {
        final byte[] bytes;

        ByteQueueItem(byte[] bytes) {
            this.bytes = bytes;
        }
    }

    class NullQueueItem implements QueueItem {
    }

    public final static String TAG = "FlutterStethoPlugin";
    private final NetworkEventReporter mEventReporter = NetworkEventReporterImpl.get();
    private final Map<String, PipedInputStream> inputs = new HashMap<>();
    private final Map<String, PipedOutputStream> outputs = new HashMap<>();
    private final Map<String, FlutterStethoInspectorResponse> responses = new HashMap<>();
    private final Map<String, LinkedBlockingQueue<QueueItem>> queues = new HashMap<>();

    /**
     * Plugin registration.
     */
    public static void registerWith(Registrar registrar) {
        Stetho.initializeWithDefaults(registrar.context());
        final MethodChannel channel = new MethodChannel(registrar.messenger(), "flutter_stetho");
        channel.setMethodCallHandler(new FlutterStethoPlugin());
    }

    @Override
    public void onMethodCall(final MethodCall call, Result result) {
        switch (call.method) {
            case "requestWillBeSent":
                mEventReporter.requestWillBeSent(new FlutterStethoInspectorRequest(
                        ((Map<String, Object>) call.arguments)
                ));
                break;
            case "responseHeadersReceived":
                FlutterStethoInspectorResponse response = new FlutterStethoInspectorResponse(((Map<String, Object>) call.arguments));
                responses.put(response.requestId(), response);
                mEventReporter.responseHeadersReceived(response);
                break;
            case "interpretResponseStream":
                final String interpretedResponseId = ((String) call.arguments);

                try {
                    final PipedInputStream in = new PipedInputStream();
                    final PipedOutputStream out = new PipedOutputStream(in);
                    final LinkedBlockingQueue<QueueItem> queue = new LinkedBlockingQueue<>();
                    inputs.put(interpretedResponseId, in);
                    outputs.put(interpretedResponseId, out);
                    queues.put(interpretedResponseId, queue);

                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                QueueItem item;
                                while ((item = queue.take()) instanceof ByteQueueItem) {
                                    out.write(((ByteQueueItem) item).bytes);
                                }
                                out.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }, interpretedResponseId + "src").start();

                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            final InputStream in2 = mEventReporter.interpretResponseStream(
                                    interpretedResponseId,
                                    responses.get(interpretedResponseId).firstHeaderValue("content-type"),
                                    null,
                                    in,
                                    new DefaultResponseHandler(mEventReporter, interpretedResponseId));
                            try {
                                int item;
                                while ((item = in2.read()) != -1) ;
                                in.close();
                                in2.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }, interpretedResponseId + "dst").start();
                } catch (IOException e) {
                    mEventReporter.responseReadFailed(interpretedResponseId, e.getMessage());
                }

                break;
            case "onData":
                Map<String, Object> arguments = (Map<String, Object>) call.arguments;
                final String dataId = ((String) arguments.get("id"));
                final byte[] data = ((byte[]) arguments.get("data"));
                final LinkedBlockingQueue<QueueItem> queue = queues.get(dataId);
                try {
                    queue.put(new ByteQueueItem(data));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                break;
            case "onDone":
                final String onDoneId = ((String) call.arguments);
                final PipedOutputStream pipedOutputStream = outputs.get(onDoneId);
                final LinkedBlockingQueue<QueueItem> doneQueue = queues.get(onDoneId);
                try {
                    doneQueue.put(new NullQueueItem());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                break;
            case "responseReadFinished":
                mEventReporter.responseReadFinished(((String) call.arguments));
                break;
            case "responseReadFailed":
                final List<String> idError = ((List<String>) call.arguments);
                mEventReporter.responseReadFailed(idError.get(0), idError.get(1));
                break;
            default:
                result.notImplemented();
                break;
        }
    }
}
