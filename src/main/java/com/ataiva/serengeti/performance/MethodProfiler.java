package com.ataiva.serengeti.performance;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides method-level performance profiling capabilities.
 * This class allows for detailed instrumentation of method execution times
 * with minimal overhead, supporting both proxy-based and manual profiling.
 */
public class MethodProfiler {
    private static final Logger LOGGER = Logger.getLogger(MethodProfiler.class.getName());
    
    // Singleton instance
    private static final MethodProfiler INSTANCE = new MethodProfiler();
    
    // Reference to the performance profiler
    private final PerformanceProfiler profiler = PerformanceProfiler.getInstance();
    
    // Configuration
    private final ProfilingConfiguration config = ProfilingConfiguration.getInstance();
    
    // Cache for method signatures to avoid string concatenation overhead
    private final ConcurrentHashMap<Method, String> methodSignatureCache = new ConcurrentHashMap<>();
    
    // Cache for instrumented proxies
    private final ConcurrentHashMap<Object, Object> proxyCache = new ConcurrentHashMap<>();
    
    /**
     * Private constructor to enforce singleton pattern.
     */
    private MethodProfiler() {
        // Initialize with default settings
    }
    
    /**
     * Gets the singleton instance of the method profiler.
     *
     * @return The method profiler instance
     */
    public static MethodProfiler getInstance() {
        return INSTANCE;
    }
    
    /**
     * Creates a proxy that profiles all method calls on the target object.
     *
     * @param <T> The type of the target object
     * @param target The target object to profile
     * @param interfaceClass The interface class that the target implements
     * @param component The component name for metrics
     * @return A proxy that profiles all method calls
     */
    @SuppressWarnings("unchecked")
    public <T> T createProfilingProxy(T target, Class<T> interfaceClass, String component) {
        if (target == null || interfaceClass == null) {
            return target;
        }
        
        // Check if we already have a proxy for this target
        if (proxyCache.containsKey(target)) {
            return (T) proxyCache.get(target);
        }
        
        // Create a new proxy
        T proxy = (T) Proxy.newProxyInstance(
            interfaceClass.getClassLoader(),
            new Class<?>[] { interfaceClass },
            new ProfilingInvocationHandler(target, component)
        );
        
        // Cache the proxy
        proxyCache.put(target, proxy);
        
        return proxy;
    }
    
    /**
     * Profiles the execution of a code block.
     *
     * @param component The component name for metrics
     * @param operation The operation name for metrics
     * @param runnable The code block to profile
     */
    public void profile(String component, String operation, Runnable runnable) {
        if (!config.isEnabled() || !config.shouldSample()) {
            runnable.run();
            return;
        }
        
        String timerId = profiler.startTimer(component, operation);
        try {
            runnable.run();
        } finally {
            profiler.stopTimer(timerId, operation + ".execution-time");
        }
    }
    
    /**
     * Profiles the execution of a code block and returns its result.
     *
     * @param <T> The return type of the code block
     * @param component The component name for metrics
     * @param operation The operation name for metrics
     * @param supplier The code block to profile
     * @return The result of the code block
     */
    public <T> T profileWithResult(String component, String operation, Supplier<T> supplier) {
        if (!config.isEnabled() || !config.shouldSample()) {
            return supplier.get();
        }
        
        String timerId = profiler.startTimer(component, operation);
        try {
            T result = supplier.get();
            return result;
        } finally {
            profiler.stopTimer(timerId, operation + ".execution-time");
        }
    }
    
    /**
     * Profiles a method call with the given parameters.
     *
     * @param component The component name for metrics
     * @param methodName The method name for metrics
     * @param args The method arguments
     * @return A timer ID for stopping the timer
     */
    public String profileMethodStart(String component, String methodName, Object... args) {
        if (!config.isEnabled() || !config.shouldSample()) {
            return null;
        }
        
        String operation = methodName;
        if (args != null && args.length > 0) {
            // Record argument types for more detailed profiling
            operation += "(" + Arrays.stream(args)
                .map(arg -> arg == null ? "null" : arg.getClass().getSimpleName())
                .reduce((a, b) -> a + "," + b)
                .orElse("") + ")";
        }
        
        return profiler.startTimer(component, operation);
    }
    
    /**
     * Stops profiling a method call.
     *
     * @param timerId The timer ID returned by profileMethodStart
     * @param methodName The method name for metrics
     * @return The duration in milliseconds
     */
    public double profileMethodEnd(String timerId, String methodName) {
        if (timerId == null) {
            return -1;
        }
        
        return profiler.stopTimer(timerId, methodName + ".execution-time");
    }
    
    /**
     * Gets the method signature for a method.
     *
     * @param method The method
     * @return The method signature
     */
    private String getMethodSignature(Method method) {
        return methodSignatureCache.computeIfAbsent(method, m -> {
            StringBuilder sb = new StringBuilder();
            sb.append(m.getName());
            sb.append("(");
            
            Class<?>[] paramTypes = m.getParameterTypes();
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(paramTypes[i].getSimpleName());
            }
            
            sb.append(")");
            return sb.toString();
        });
    }
    
    /**
     * Invocation handler that profiles method calls.
     */
    private class ProfilingInvocationHandler implements InvocationHandler {
        private final Object target;
        private final String component;
        private final Map<Method, String> methodNames = new HashMap<>();
        
        public ProfilingInvocationHandler(Object target, String component) {
            this.target = target;
            this.component = component;
        }
        
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!config.isEnabled() || !config.shouldSample()) {
                return method.invoke(target, args);
            }
            
            // Skip Object methods
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(target, args);
            }
            
            // Get method signature
            String methodSignature = methodNames.computeIfAbsent(method, MethodProfiler.this::getMethodSignature);
            
            // Start timer
            String timerId = profiler.startTimer(component, methodSignature);
            
            try {
                // Invoke the method
                Object result = method.invoke(target, args);
                
                // Record method success
                profiler.incrementCounter(component, methodSignature, "success");
                
                return result;
            } catch (Throwable t) {
                // Record method failure
                profiler.incrementCounter(component, methodSignature, "failure");
                
                // Log the exception
                LOGGER.log(Level.FINE, "Method invocation failed", t);
                
                // Rethrow the exception
                throw t;
            } finally {
                // Stop timer
                profiler.stopTimer(timerId, methodSignature + ".execution-time");
            }
        }
    }
    
    /**
     * Annotation for marking methods to be profiled.
     */
    public @interface Profiled {
        /**
         * The component name for metrics.
         * If not specified, the class name will be used.
         *
         * @return The component name
         */
        String component() default "";
        
        /**
         * The operation name for metrics.
         * If not specified, the method name will be used.
         *
         * @return The operation name
         */
        String operation() default "";
    }
}