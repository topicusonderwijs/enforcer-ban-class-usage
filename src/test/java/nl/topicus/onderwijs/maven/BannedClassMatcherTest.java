package nl.topicus.onderwijs.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class BannedClassMatcherTest
{
	private final BannedClassMatcher matcher = new BannedClassMatcher(
		List.of("javax.servlet.*", "javax.transaction.Status", "javax.annotation.Resource*"));

	@Test
	void shouldMatchPrefixesAndExactNames()
	{
		assertThat(matcher.matches("javax.servlet.http.HttpServlet")).isTrue();
		assertThat(matcher.matches("javax.transaction.Status")).isTrue();
		assertThat(matcher.matches("javax.annotation.Resources")).isTrue();
		assertThat(matcher.matches("javax.transaction.StatusX")).isFalse();
		assertThat(matcher.matches("javax.servle")).isFalse();
		assertThat(matcher.matches("java.lang.String")).isFalse();
	}

	@Test
	void shouldTreatAsteriskInTheMiddleLiterally()
	{
		BannedClassMatcher literal = new BannedClassMatcher(List.of("javax.*.Foo"));

		assertThat(literal.matches("javax.*.Foo")).isTrue();
		assertThat(literal.matches("javax.bar.Foo")).isFalse();
	}

	@Test
	void shouldSkipClassWithoutBannedReferences()
	{
		byte[] classFile = generate(cw -> cw.visitField(Opcodes.ACC_PRIVATE, "name",
			"Ljava/lang/String;", "Ljava/util/List<Ljavax/ejb/EJB;>;", null));

		assertThat(mayReference(classFile)).isFalse();
		assertThat(collectBanned(classFile)).isEmpty();
	}

	@Test
	void shouldNotSkipMethodCall()
	{
		byte[] classFile = generate(cw -> {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
			mv.visitCode();
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "nl/Helper", "status", "()I", false);
			mv.visitFieldInsn(Opcodes.GETSTATIC, "nl/Helper", "STATUS",
				"Ljavax/transaction/Status;");
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		});

		assertThat(mayReference(classFile)).isTrue();
		assertThat(collectBanned(classFile)).containsExactly("javax.transaction.Status");
	}

	@Test
	void shouldNotSkipGenericSignature()
	{
		byte[] classFile = generate(cw -> cw.visitField(Opcodes.ACC_PRIVATE, "servlets",
			"Ljava/util/List;", "Ljava/util/List<Ljavax/servlet/Servlet;>;", null));

		assertThat(mayReference(classFile)).isTrue();
		assertThat(collectBanned(classFile)).containsExactly("javax.servlet.Servlet");
	}

	@Test
	void shouldNotSkipClassLiteralAndAnnotation()
	{
		byte[] classFile = generate(cw -> {
			cw.visitAnnotation("Ljavax/annotation/Resources;", true).visitEnd();
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "type", "()Ljava/lang/Class;",
				null, null);
			mv.visitCode();
			mv.visitLdcInsn(Type.getObjectType("javax/servlet/Filter"));
			mv.visitInsn(Opcodes.ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		});

		assertThat(mayReference(classFile)).isTrue();
		assertThat(collectBanned(classFile)).containsExactlyInAnyOrder(
			"javax.annotation.Resources", "javax.servlet.Filter");
	}

	@Test
	void shouldNotSkipAnythingForNonAsciiBannedClass()
	{
		BannedClassMatcher nonAscii = new BannedClassMatcher(List.of("javax.cafè.*"));
		byte[] classFile = generate(cw -> {
		});

		assertThat(nonAscii.mayReferenceBannedClass(classFile, 10,
			new ClassReader(classFile).header)).isTrue();
	}

	private boolean mayReference(byte[] classFile)
	{
		return matcher.mayReferenceBannedClass(classFile, 10, new ClassReader(classFile).header);
	}

	private Set<String> collectBanned(byte[] classFile)
	{
		ClassDependencyCollector collector = new ClassDependencyCollector();
		new ClassReader(classFile).accept(collector, ClassReader.SKIP_DEBUG);
		return collector.getDeps()
			.stream()
			.map(classname -> classname.replace("/", "."))
			.filter(matcher::matches)
			.collect(Collectors.toSet());
	}

	private static byte[] generate(Consumer<ClassWriter> body)
	{
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "nl/Generated", null, "java/lang/Object", null);
		body.accept(cw);
		cw.visitEnd();
		return cw.toByteArray();
	}
}
