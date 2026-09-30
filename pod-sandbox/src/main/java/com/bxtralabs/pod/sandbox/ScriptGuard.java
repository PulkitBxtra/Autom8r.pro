package com.bxtralabs.pod.sandbox;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Types;

import java.util.List;
import java.util.Set;

// Compile-time checks on a Code step's script: only data work is allowed (text, numbers, dates,
// maps, lists, JSON). Anything reaching outside the script (System, threads, files, network,
// reflection, class loading, compiling more code) is refused with the line it's on.
//
// Groovy decides most calls at run time, so a check on the source can't be complete; it's an
// extra layer that turns obvious attempts and honest mistakes into a clear message. The real
// boundary is the separate, secret-less process ScriptRunner runs in.
final class ScriptGuard {

    private ScriptGuard() {
    }

    // A refused construct. Reported like a syntax error, with its line.
    static final class Blocked extends SyntaxException {
        Blocked(String message, ASTNode node) {
            super(message, node == null ? -1 : node.getLineNumber(), node == null ? -1 : node.getColumnNumber());
        }
    }

    // Methods that reach the runtime or reflection, whatever they're called on.
    static final Set<String> BLOCKED_METHODS = Set.of(
            "getClass", "getMetaClass", "setMetaClass", "invokeMethod", "getProperty", "setProperty", "getProperties",
            "getMetaPropertyValues", "respondsTo", "hasProperty", "mixin",
            "forName", "loadClass", "defineClass", "getClassLoader", "newInstance",
            "getDeclaredMethod", "getDeclaredMethods", "getDeclaredField", "getDeclaredFields",
            "getDeclaredConstructor", "getDeclaredConstructors", "getMethod", "getMethods", "getField", "getFields",
            "getConstructor", "getConstructors",
            "exit", "halt", "exec", "execute", "evaluate", "run", "toURL", "toURI");

    static final Set<String> BLOCKED_PROPERTIES = Set.of(
            "class", "metaClass", "classLoader", "declaredMethods", "declaredFields", "declaredConstructors",
            "methods", "fields", "constructors", "properties", "metaPropertyValues", "protectionDomain");

    private static final Set<String> BLOCKED_JAVA_LANG = Set.of(
            "System", "Runtime", "Thread", "ThreadGroup", "ThreadLocal", "InheritableThreadLocal", "Class",
            "ClassLoader", "ClassValue", "Compiler", "Process", "ProcessBuilder", "ProcessHandle", "SecurityManager",
            "StackWalker", "Module", "ModuleLayer", "Package", "ScopedValue");

    private static final Set<String> BLOCKED_GROOVY_LANG = Set.of(
            "GroovyShell", "GroovyClassLoader", "GroovySystem", "GroovyCodeSource", "Script", "Interceptor",
            "Grab", "GrabConfig", "GrabExclude", "GrabResolver", "Grapes");

    private static final Set<String> BLOCKED_JAVA_UTIL = Set.of("ServiceLoader", "Timer", "TimerTask");

    // Packages a script may use types from (not their subpackages, unless listed).
    private static final Set<String> ALLOWED_PACKAGES = Set.of(
            "java.lang", "java.util", "java.util.regex", "java.util.function", "java.util.stream", "java.math", "java.text",
            "java.time", "java.time.format", "java.time.temporal", "java.time.chrono", "groovy.lang", "groovy.json");

    // Whether a script may name this type (in a call, a `new`, a cast or a declaration).
    static boolean allowedType(String name) {
        String pkg = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "";
        String simple = name.substring(name.lastIndexOf('.') + 1);
        if (pkg.isEmpty()) {
            return true; // primitives, the script itself, its own methods' generics
        }
        if (!ALLOWED_PACKAGES.contains(pkg)) {
            return false;
        }
        return switch (pkg) {
            case "java.lang" -> !BLOCKED_JAVA_LANG.contains(simple.split("\\$")[0]);
            case "groovy.lang" -> !BLOCKED_GROOVY_LANG.contains(simple) && !simple.contains("MetaClass")
                    && !simple.startsWith("Meta");
            case "java.util" -> !BLOCKED_JAVA_UTIL.contains(simple);
            default -> true;
        };
    }

    // Before any AST transformation runs: no annotations (@ASTTest, @Grab and friends run code
    // while compiling), no classes, and only allowed imports.
    static CompilationCustomizer beforeTransforms() {
        return new CompilationCustomizer(CompilePhase.CONVERSION) {
            @Override
            public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
                if (!classNode.isScript()) {
                    report(source, new Blocked("Code steps can't define classes; use maps and closures instead", classNode));
                    return;
                }
                ModuleNode module = source.getAST();
                for (ImportNode i : module.getImports()) {
                    if (!allowedType(i.getClassName())) {
                        report(source, new Blocked(i.getClassName() + " isn't available in Code steps", i));
                    }
                }
                for (ImportNode i : module.getStaticImports().values()) {
                    if (!allowedType(i.getClassName())) {
                        report(source, new Blocked(i.getClassName() + " isn't available in Code steps", i));
                    }
                }
                for (ImportNode i : module.getStarImports()) {
                    if (!ALLOWED_PACKAGES.contains(i.getPackageName().replaceAll("\\.$", ""))) {
                        report(source, new Blocked("import " + i.getPackageName() + "* isn't available in Code steps", i));
                    }
                }
                for (ImportNode i : module.getStaticStarImports().values()) {
                    if (!allowedType(i.getClassName())) {
                        report(source, new Blocked(i.getClassName() + " isn't available in Code steps", i));
                    }
                }
                new ClassCodeVisitorSupport() {
                    @Override
                    protected SourceUnit getSourceUnit() {
                        return source;
                    }

                    @Override
                    public void visitAnnotations(AnnotatedNode node) {
                        // The compiler marks the script's own methods @Generated; those have no line.
                        node.getAnnotations().stream().filter(a -> a.getLineNumber() > 0).findFirst().ifPresent(a ->
                                report(source, new Blocked("Annotations (@" + a.getClassNode().getNameWithoutPackage()
                                        + ") aren't available in Code steps", a)));
                    }

                    @Override
                    protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
                        for (Parameter p : node.getParameters()) {
                            visitAnnotations(p);
                        }
                        super.visitConstructorOrMethod(node, isConstructor);
                    }

                    @Override
                    public void visitClosureExpression(ClosureExpression expression) {
                        if (expression.getParameters() != null) {
                            for (Parameter p : expression.getParameters()) {
                                visitAnnotations(p);
                            }
                        }
                        super.visitClosureExpression(expression);
                    }
                }.visitClass(classNode);
            }
        };
    }

    // Once names are resolved to types: what the script calls, creates and reads.
    static CompilationCustomizer afterResolving() {
        return new CompilationCustomizer(CompilePhase.SEMANTIC_ANALYSIS) {
            @Override
            public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
                new ClassCodeVisitorSupport() {
                    @Override
                    protected SourceUnit getSourceUnit() {
                        return source;
                    }

                    private void type(ClassNode type, ASTNode at) {
                        if (type == null || type.isGenericsPlaceHolder()) return;
                        ClassNode t = type;
                        while (t.isArray()) t = t.getComponentType();
                        if (!allowedType(t.getName())) {
                            report(source, new Blocked(t.getNameWithoutPackage() + " isn't available in Code steps", at));
                        }
                    }

                    private void method(String name, ASTNode at) {
                        if (name == null) {
                            report(source, new Blocked("Calling a method by a computed name isn't available in Code steps", at));
                        } else if (BLOCKED_METHODS.contains(name)) {
                            report(source, new Blocked(name + "() isn't available in Code steps", at));
                        }
                    }

                    @Override
                    protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
                        // The compiler's own main() and constructors (no line): not the user's code.
                        // run() holds the script's body, so it's checked, but its name is the compiler's.
                        boolean body = "run".equals(node.getName()) && node.getParameters().length == 0;
                        if (node.getLineNumber() < 1 && !body) {
                            return;
                        }
                        if (!body) {
                            if (BLOCKED_METHODS.contains(node.getName())) {
                                report(source, new Blocked("A method can't be called " + node.getName() + " in Code steps", node));
                            }
                            type(node.getReturnType(), node);
                            for (Parameter p : node.getParameters()) type(p.getType(), p);
                        }
                        super.visitConstructorOrMethod(node, isConstructor);
                    }

                    @Override
                    public void visitMethodCallExpression(MethodCallExpression call) {
                        method(call.getMethodAsString(), call);
                        super.visitMethodCallExpression(call);
                    }

                    @Override
                    public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
                        method(call.getMethod(), call);
                        type(call.getOwnerType(), call);
                        super.visitStaticMethodCallExpression(call);
                    }

                    @Override
                    public void visitMethodPointerExpression(MethodPointerExpression expression) {
                        method(expression.getMethodName() instanceof ConstantExpression c ? String.valueOf(c.getValue()) : null, expression);
                        super.visitMethodPointerExpression(expression);
                    }

                    @Override
                    public void visitMethodReferenceExpression(MethodReferenceExpression expression) {
                        visitMethodPointerExpression(expression);
                    }

                    @Override
                    public void visitPropertyExpression(PropertyExpression expression) {
                        String name = expression.getPropertyAsString();
                        if (name == null) {
                            report(source, new Blocked("Reading a property by a computed name isn't available in Code steps", expression));
                        } else if (BLOCKED_PROPERTIES.contains(name)) {
                            report(source, new Blocked("." + name + " isn't available in Code steps", expression));
                        }
                        super.visitPropertyExpression(expression);
                    }

                    @Override
                    public void visitAttributeExpression(AttributeExpression expression) {
                        report(source, new Blocked("The .@ operator isn't available in Code steps", expression));
                    }

                    @Override
                    public void visitBinaryExpression(BinaryExpression expression) {
                        if (expression.getOperation().getType() == Types.LEFT_SQUARE_BRACKET
                                && expression.getRightExpression() instanceof ConstantExpression c
                                && BLOCKED_PROPERTIES.contains(String.valueOf(c.getValue()))) {
                            report(source, new Blocked("['" + c.getValue() + "'] isn't available in Code steps", expression));
                        }
                        super.visitBinaryExpression(expression);
                    }

                    @Override
                    public void visitClassExpression(ClassExpression expression) {
                        type(expression.getType(), expression);
                        super.visitClassExpression(expression);
                    }

                    @Override
                    public void visitConstructorCallExpression(ConstructorCallExpression call) {
                        if (call.isUsingAnonymousInnerClass()) {
                            report(source, new Blocked("Code steps can't define classes; use maps and closures instead", call));
                        }
                        type(call.getType(), call);
                        super.visitConstructorCallExpression(call);
                    }

                    @Override
                    public void visitCastExpression(CastExpression expression) {
                        type(expression.getType(), expression);
                        super.visitCastExpression(expression);
                    }

                    @Override
                    public void visitDeclarationExpression(DeclarationExpression expression) {
                        List<Expression> targets = expression.isMultipleAssignmentDeclaration()
                                ? expression.getTupleExpression().getExpressions()
                                : List.of(expression.getVariableExpression());
                        for (Expression t : targets) {
                            if (t instanceof VariableExpression v) type(v.getOriginType(), expression);
                        }
                        super.visitDeclarationExpression(expression);
                    }

                    @Override
                    public void visitClosureExpression(ClosureExpression expression) {
                        if (expression.getParameters() != null) {
                            for (Parameter p : expression.getParameters()) type(p.getType(), p);
                        }
                        super.visitClosureExpression(expression);
                    }
                }.visitClass(classNode);
            }
        };
    }

    private static void report(SourceUnit source, Blocked blocked) {
        source.getErrorCollector().addErrorAndContinue(new SyntaxErrorMessage(blocked, source));
    }
}
