package org.logstash.dissect;

import com.logstash.Event;
import com.logstash.ext.JrubyEventExtLibrary.RubyEvent;
import org.jruby.*;
import org.jruby.anno.JRubyMethod;
import org.jruby.internal.runtime.methods.DynamicMethod;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.load.Library;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


public class JavaDissectorLibrary implements Library {

    @Override
    public void load(Ruby runtime, boolean wrap) throws IOException {
        RubyModule module = runtime.defineModule("LogStash");

        RubyClass clazz = runtime.defineClassUnder("Dissector", runtime.getObject(), new ObjectAllocator() {
            public IRubyObject allocate(Ruby runtime, RubyClass rubyClass) {
                return new RubyDissect(runtime, rubyClass);
            }
        }, module);

        clazz.defineAnnotatedMethods(RubyDissect.class);
    }

    public static class RubyDissect extends RubyObject {
//        private Dissector dissector;
        private HashMap<RubyString, Dissector> dissectors = new HashMap<>();

        public RubyDissect(Ruby runtime, RubyClass klass) {
            super(runtime, klass);
        }

        public RubyDissect(Ruby runtime) {
            this(runtime, runtime.getModule("LogStash").getClass("Dissector"));
        }

        // def initialize(mapping)
        @JRubyMethod(name = "initialize", required = 1)
        public IRubyObject ruby_initialize(ThreadContext ctx, IRubyObject mapping) {
            RubyHash maps = (RubyHash) mapping;

            maps.visitAll(new RubyHash.Visitor() {
                @Override
                public void visit(IRubyObject srcField, IRubyObject mapping) {
                    dissectors.put(srcField.asString(), new Dissector(mapping.asJavaString()));
                }
            });
            return ctx.nil;
        }

        // def dissect(event, self)
        @JRubyMethod(name = "dissect", required = 2)
        public IRubyObject dissect(ThreadContext ctx, IRubyObject arg1, IRubyObject arg2) {
            RubyEvent re = (RubyEvent) arg1;
            if(re.ruby_cancelled(ctx).isTrue()) {
                return ctx.nil;
            }
            Event e = re.getEvent();
            RubyObject plugin = (RubyObject) arg2;
            RubyObject logger = (RubyObject) getLoggerObject(ctx, plugin);
            try {
                // @logger.debug? && @logger.debug("Event before dissection", "event" => event.to_hash)
                if(logLevelEnabled(ctx, logger, "debug")) {
                    logDebug(ctx, logger, "Event before dissection", buildDebugEventMap(ctx, re));
                }
                for (Map.Entry<RubyString, Dissector> entry : dissectors.entrySet()) {
                    RubyString key = entry.getKey();
                    if (e.includes(key.toString())) {
                        // use ruby event here because we want the bytelist bytes
                        // from the ruby string without converting to Java
                        RubyString src = re.ruby_get_field(ctx, key).asString();
                        entry.getValue().dissect(src.getBytes(), e);
                    } else {
                        HashMap<IRubyObject, IRubyObject> map = new HashMap<>(2);
                        map.put(rubyString(ctx, "key"), key);
                        map.put(rubyString(ctx, "event"), re.ruby_to_hash(ctx));
                        logWarn(ctx, logger, "Dissector mapping, key not found in event", map);
                    }
                }
                invoke_filter_matched(ctx, getMethod(plugin, "filter_matched"), plugin, re);
                // @logger.debug? && @logger.debug("Event after dissection", "event" => event.to_hash)
                if(logLevelEnabled(ctx, logger, "debug")) {
                    logDebug(ctx, logger, "Event after dissection", buildDebugEventMap(ctx, re));
                }
            } catch (Exception ex) {
                addTags(ctx, plugin, e);
                logException(ctx, logger, ex);
            }
            return ctx.nil;
        }

        // def dissect_multi(events, self)
        @JRubyMethod(name = "dissect_multi", required = 2)
        public IRubyObject dissect_multi(ThreadContext ctx, IRubyObject arg1, IRubyObject arg2) {
            RubyArray events = (RubyArray) arg1;
            for(IRubyObject event : events.toJavaArray()) {
                dissect(ctx, event, arg2);
            }
            return ctx.nil;
        }

        private DynamicMethod getMethod(RubyObject target, String name) {
            return target.getMetaClass().searchMethod(name);
        }

        private IRubyObject invoke_filter_matched(ThreadContext ctx, DynamicMethod m, RubyObject plugin, RubyEvent event) {
            if (!m.isUndefined()) {
                return m.call(ctx, plugin, plugin.getMetaClass(), "filter_matched", new IRubyObject[]{event});
            }
            return ctx.nil;
        }

        private IRubyObject addTags(ThreadContext ctx, RubyObject plugin, Event event) {
            DynamicMethod m = getMethod(plugin, "tag_on_failure");
            if (m.isUndefined()) {
                return ctx.nil;
            }
            IRubyObject obj = m.call(ctx, plugin, plugin.getMetaClass(), "tag_on_failure");
            if (obj instanceof RubyArray) {
                RubyArray tags = (RubyArray) obj;
                for (IRubyObject t : tags.toJavaArray()) {
                    event.tag(t.toString());
                }
            }
            return ctx.nil;
        }

        private IRubyObject getLoggerObject(ThreadContext ctx, RubyObject plugin) {
            DynamicMethod m = getMethod(plugin, "logger");
            if (m.isUndefined()) {
                return ctx.nil;
            }
            return m.call(ctx, plugin, plugin.getMetaClass(), "logger");
        }

        private IRubyObject logException(ThreadContext ctx, RubyObject logger, Throwable ex) {
            Ruby ruby = ctx.runtime;
            HashMap<IRubyObject, IRubyObject> map = new HashMap<>(2);
            map.put(rubyString(ruby, "exception"), DissectorError.message(ruby, ex));
            map.put(rubyString(ruby, "backtrace"), DissectorError.backtrace(ruby, ex));

            return logError(ctx, logger, "Dissect threw an exception", map);
        }

        private IRubyObject logError(ThreadContext ctx, RubyObject logger, String message, Map<IRubyObject, IRubyObject> map) {
            return logWithLevel(ctx, logger, "error", message, map);
        }

        private IRubyObject logWarn(ThreadContext ctx, RubyObject logger, String message, Map<IRubyObject, IRubyObject> map) {
            return logWithLevel(ctx, logger, "warn", message, map);
        }

        private IRubyObject logDebug(ThreadContext ctx, RubyObject logger, String message, Map<IRubyObject, IRubyObject> map) {
            return logWithLevel(ctx, logger, "debug", message, map);
        }

        private IRubyObject logWithLevel(ThreadContext ctx, RubyObject logger, String level, String message, Map<IRubyObject, IRubyObject> map) {
            Ruby ruby = ctx.runtime;
            DynamicMethod m = getMethod(logger, level);
            if (m.isUndefined()) {
                return ctx.nil;
            }
            RubyHash h = RubyHash.newHash(ruby, map, ctx.nil);
            m.call(ctx, logger, logger.getMetaClass(), level, new IRubyObject[]{rubyString(ruby, message), h});
            return ctx.nil;
        }

        private boolean logLevelEnabled(ThreadContext ctx, RubyObject logger, String level) {
            String level_p = level + "?";
            DynamicMethod m = getMethod(logger, level_p);
            if (m.isUndefined()) {
                return false;
            }
            RubyBoolean result = (RubyBoolean) m.call(ctx, logger, logger.getMetaClass(), level_p);
            return result.isTrue();
        }

        private RubyString rubyString(Ruby ruby, String s) {
            return RubyString.newString(ruby, s);
        }

        private RubyString rubyString(ThreadContext ctx, String s) {
            return RubyString.newString(ctx.runtime, s);
        }

        private Map<IRubyObject, IRubyObject> buildDebugEventMap(ThreadContext ctx, RubyEvent re) throws Exception {
            HashMap<IRubyObject, IRubyObject> map = new HashMap<>(1);
            map.put(rubyString(ctx, "event"), re.ruby_to_hash(ctx));
            return map;
        }
    }
}