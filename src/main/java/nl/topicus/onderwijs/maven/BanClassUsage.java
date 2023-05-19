package nl.topicus.onderwijs.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.time.StopWatch;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.plugins.enforcer.AbstractResolveDependencies;
import org.codehaus.mojo.enforcer.Dependency;
import org.objectweb.asm.ClassReader;

public class BanClassUsage extends AbstractResolveDependencies
{
	private List<String> bannedClasses;

	private List<Dependency> dependencies;

	private List<String> scopes;

	@Override
	protected void handleArtifacts(Set<Artifact> artifacts) throws EnforcerRuleException
	{
		List<IgnorableDependency> ignorableDependencies = new ArrayList<>();
		if (dependencies != null)
		{
			getLog().info("BanClassUsage has ignorable dependencies");
			for (Dependency dependency : dependencies)
			{
				getLog().info("Adding ignorable dependency: " + dependency);
				IgnorableDependency ignorableDependency = new IgnorableDependency();
				if (dependency.getGroupId() != null)
				{
					ignorableDependency.groupId = Pattern.compile(asRegex(dependency.getGroupId()));
				}
				if (dependency.getArtifactId() != null)
				{
					ignorableDependency.artifactId =
						Pattern.compile(asRegex(dependency.getArtifactId()));
				}
				if (dependency.getType() != null)
				{
					ignorableDependency.type = Pattern.compile(asRegex(dependency.getType()));
				}
				if (dependency.getClassifier() != null)
				{
					ignorableDependency.classifier =
						Pattern.compile(asRegex(dependency.getClassifier()));
				}
				ignorableDependency.applyIgnoreClasses(dependency.getIgnoreClasses(), true);
				ignorableDependencies.add(ignorableDependency);
			}
		}

		StopWatch sw = new StopWatch();
		sw.start();
		StringBuilder error = new StringBuilder();
		for (Artifact artifact : artifacts)
		{
			if (scopes != null && !scopes.contains(artifact.getScope()))
			{
				if (getLog().isDebugEnabled())
				{
					getLog().debug("Skipping " + artifact + " due to scope");
				}
				continue;
			}

			getLog().debug("Analyzing artifact " + artifact);
			Set<String> banned = getBannedClasses(artifact, ignorableDependencies);
			if (!banned.isEmpty())
			{
				error.append("\n  Banned classes found in " + artifact.toString() + ":\n");
				banned.forEach(s -> error.append("    " + s + "\n"));
			}
		}
		sw.stop();
		getLog().debug("BanClassUsage took " + sw.getTime() + "ms");
		if (error.length() > 0)
		{
			throw new EnforcerRuleException(
				"One or more dependencies use classes that are banned:\n" + error.toString());
		}
	}

	private Set<String> getBannedClasses(Artifact artifact,
			List<IgnorableDependency> ignorableDependencies) throws EnforcerRuleException
	{
		Set<String> ret = new TreeSet<>();
		File file = artifact.getFile();
		getLog().debug("isBadArtifact() a: " + artifact + " Artifact getFile(): " + file);
		if (file == null)
		{
			// This happens if someone defines dependencies instead of dependencyManagement in a pom
			// file which packaging type is pom.
			return ret;
		}
		if (!file.getName().endsWith(".jar"))
		{
			return ret;
		}
		try (JarFile jarFile = new JarFile(file))
		{
			getLog().debug(file.getName() + " => " + file.getPath());
			for (JarEntry entry : jarFile.stream().collect(Collectors.toList()))
			{
				if (!entry.isDirectory() && entry.getName().endsWith(".class"))
				{
					try (InputStream is = jarFile.getInputStream(entry))
					{
						getLog().debug("Checking " + entry.getName());
						ClassReader reader = new ClassReader(is);
						ClassDependencyCollector collector = new ClassDependencyCollector();
						reader.accept(collector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
						collector.getDeps()
							.stream()
							.map(classname -> classname.replace("/", "."))
							.filter(
								classname -> isBanned(artifact, classname, ignorableDependencies))
							.forEach(ret::add);
					}
					catch (Exception e)
					{
						getLog().warn("Skipping " + entry.getName() + " due to " + e.getMessage());
					}
				}
			}
		}
		catch (IOException e)
		{
			throw new EnforcerRuleException("IOException while reading " + file, e);
		}
		catch (IllegalArgumentException e)
		{
			throw new EnforcerRuleException("Error while reading " + file, e);
		}
		return ret;
	}

	private boolean isBanned(Artifact artifact, String classname,
			List<IgnorableDependency> ignorableDependencies)
	{
		for (String bannedClass : bannedClasses)
		{
			if (bannedClass.endsWith("*"))
			{
				if (classname.startsWith(bannedClass.substring(0, bannedClass.length() - 1)))
					return !isIgnored(artifact, classname, ignorableDependencies);
			}
			else if (classname.equals(bannedClass))
			{
				return !isIgnored(artifact, classname, ignorableDependencies);
			}
		}
		return false;
	}

	private boolean isIgnored(Artifact artifact, String classname,
			List<IgnorableDependency> ignorableDependencies)
	{
		for (IgnorableDependency curDependency : ignorableDependencies)
		{
			if (curDependency.matchesArtifact(artifact)
				&& curDependency.matches(classname.replace(".", "/")))
				return true;
		}
		return false;
	}

	protected class IgnorableDependency
	{
		public Pattern groupId;

		public Pattern artifactId;

		public Pattern classifier;

		public Pattern type;

		public List<Pattern> ignores = new ArrayList<>();

		public IgnorableDependency applyIgnoreClasses(String[] ignores, boolean indent)
		{
			String prefix = indent ? "  " : "";
			for (String ignore : ignores)
			{
				getLog().info(prefix + "Adding ignore: " + ignore);
				ignore = ignore.replace('.', '/');
				String pattern = asRegex(ignore);
				getLog().debug(prefix + "Ignore: " + ignore + " maps to regex " + pattern);
				this.ignores.add(Pattern.compile(pattern));
			}
			return this;
		}

		public boolean matchesArtifact(Artifact dup)
		{
			return (artifactId == null || artifactId.matcher(dup.getArtifactId()).matches())
				&& (groupId == null || groupId.matcher(dup.getGroupId()).matches())
				&& (classifier == null || classifier.matcher(dup.getClassifier()).matches())
				&& (type == null || type.matcher(dup.getType()).matches());
		}

		public boolean matches(String className)
		{
			for (Pattern p : ignores)
			{
				if (p.matcher(className).matches())
				{
					return true;
				}
			}
			return false;
		}
	}
}
