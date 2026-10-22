package nl.topicus.onderwijs.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;

class ClassDependencyCollectorTest
{
	private static final Handle LAMBDA_METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC,
		"java/lang/invoke/LambdaMetafactory", "metafactory",
		"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
			+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
			+ "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
		false);

	@Test
	void shouldCollectOwnerOfStaticMethodCall()
	{
		Set<String> deps = collect(method(mv -> mv.visitMethodInsn(Opcodes.INVOKESTATIC,
			"banned/Api", "work", "()V", false)));

		assertThat(deps).contains("banned/Api");
	}

	@Test
	void shouldCollectElementTypeOfArrayOwner()
	{
		Set<String> deps = collect(method(mv -> mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
			"[Lbanned/Api;", "clone", "()Ljava/lang/Object;", false)));

		assertThat(deps).contains("banned/Api").doesNotContain("[Lbanned/Api;");
	}

	@Test
	void shouldCollectOwnerOfPrimitiveField()
	{
		Set<String> deps = collect(
			method(mv -> mv.visitFieldInsn(Opcodes.GETSTATIC, "banned/Api", "COUNT", "I")));

		assertThat(deps).contains("banned/Api");
	}

	@Test
	void shouldCollectCaughtExceptionType()
	{
		Set<String> deps = collect(method(mv -> {
			Label start = new Label();
			Label end = new Label();
			Label handler = new Label();
			mv.visitTryCatchBlock(start, end, handler, "banned/BannedException");
			mv.visitTryCatchBlock(start, end, handler, null);
			mv.visitLabel(start);
			mv.visitLabel(end);
			mv.visitLabel(handler);
		}));

		assertThat(deps).contains("banned/BannedException").doesNotContainNull();
	}

	@Test
	void shouldCollectTargetOfMethodReference()
	{
		Set<String> deps = collect(method(mv -> mv.visitInvokeDynamicInsn("run",
			"()Ljava/lang/Runnable;", LAMBDA_METAFACTORY, Type.getMethodType("()V"),
			new Handle(Opcodes.H_INVOKESTATIC, "banned/Api", "work", "(Lbanned/Arg;)V", false),
			Type.getMethodType("()V"))));

		assertThat(deps).contains("banned/Api", "banned/Arg");
	}

	@Test
	void shouldCollectLdcHandleAndMethodType()
	{
		Set<String> deps = collect(method(mv -> {
			mv.visitLdcInsn(new Handle(Opcodes.H_GETSTATIC, "banned/Api", "VALUE",
				"Lbanned/Value;", false));
			mv.visitLdcInsn(Type.getMethodType("(Lbanned/Arg;)Lbanned/Result;"));
		}));

		assertThat(deps).contains("banned/Api", "banned/Value", "banned/Arg", "banned/Result");
	}

	@Test
	void shouldCollectLdcConstantDynamic()
	{
		Set<String> deps = collect(method(mv -> mv.visitLdcInsn(new ConstantDynamic("value",
			"Lbanned/Value;", new Handle(Opcodes.H_INVOKESTATIC, "banned/Bootstraps", "value",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)"
					+ "Lbanned/Value;",
				false),
			Type.getObjectType("banned/Arg")))));

		assertThat(deps).contains("banned/Value", "banned/Bootstraps", "banned/Arg");
	}

	@Test
	void shouldCollectNestedClassOfParameterizedOuterClass()
	{
		Set<String> deps = collect(generate(cw -> cw.visitField(Opcodes.ACC_PRIVATE, "list",
			"Ljava/util/List;", "Ljava/util/List<Lbanned/Outer<Ljava/lang/String;>.Inner;>;", null)));

		assertThat(deps).contains("banned/Outer", "banned/Outer$Inner", "java/lang/String")
			.doesNotContain("Inner", "java/lang/String$Inner");
	}

	@Test
	void shouldCollectAnnotationOnRecordComponent()
	{
		Set<String> deps = collect(generate(cw -> {
			RecordComponentVisitor component = cw.visitRecordComponent("x", "I", null);
			component.visitAnnotation("Lbanned/OnlyOnComponent;", true).visitEnd();
			component.visitEnd();
		}));

		assertThat(deps).contains("banned/OnlyOnComponent");
	}

	private static Set<String> collect(byte[] classFile)
	{
		ClassDependencyCollector collector = new ClassDependencyCollector();
		new ClassReader(classFile).accept(collector,
			ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return collector.getDeps();
	}

	private static byte[] method(Consumer<MethodVisitor> body)
	{
		return generate(cw -> {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V",
				null, null);
			mv.visitCode();
			body.accept(mv);
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(4, 0);
			mv.visitEnd();
		});
	}

	private static byte[] generate(Consumer<ClassWriter> body)
	{
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "nl/Generated", null, "java/lang/Object", null);
		body.accept(cw);
		cw.visitEnd();
		return cw.toByteArray();
	}
}
