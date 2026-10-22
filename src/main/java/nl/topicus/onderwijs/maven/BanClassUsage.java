package nl.topicus.onderwijs.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang3.time.StopWatch;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.enforcer.rule.api.AbstractEnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.SessionData;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.objectweb.asm.ClassReader;

@Named("banClassUsage")
public class BanClassUsage extends AbstractEnforcerRule
{
	/** Offset of the constant pool, after magic, minor_version, major_version and its count. */
	private static final int CONSTANT_POOL_START = 10;

	private List<String> bannedClasses;

	private List<Dependency> dependencies;

	private List<String> scopes;

	private MavenSession session;

	private RepositorySystem repositorySystem;

	private DependencyGraphBuilder graphBuilder;

	@Inject
	public BanClassUsage(MavenSession session, RepositorySystem repositorySystem,
			DependencyGraphBuilder graphBuilder)
	{
		this.session = session;
		this.repositorySystem = repositorySystem;
		this.graphBuilder = graphBuilder;
	}

	@Override
	public void execute() throws EnforcerRuleException
	{

		ProjectBuildingRequest buildingRequest =
			new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
		buildingRequest.setProject(session.getCurrentProject());

		handleArtifacts(getDependenciesToCheck(buildingRequest));
	}

	private Set<Artifact> getDependenciesToCheck(ProjectBuildingRequest buildingRequest)
			throws EnforcerRuleException
	{
		Set<Artifact> dependencies = null;
		try
		{
			DependencyNode node = graphBuilder.buildDependencyGraph(buildingRequest, null);
			dependencies = getAllDescendants(node);
		}
		catch (DependencyGraphBuilderException e)
		{
			throw new EnforcerRuleException(e.getMessage(), e);
		}
		return dependencies;
	}

	private Set<Artifact> getAllDescendants(DependencyNode node)
	{
		Set<Artifact> children = null;
		if (node.getChildren() != null)
		{
			children = new HashSet<>();
			for (DependencyNode depNode : node.getChildren())
			{
				try
				{
					Artifact artifact = depNode.getArtifact();
					resolveArtifact(artifact);
					children.add(artifact);

					Set<Artifact> subNodes = getAllDescendants(depNode);

					if (subNodes != null)
					{
						children.addAll(subNodes);
					}
				}
				catch (ArtifactResolutionException e)
				{
					getLog().warn(e.getMessage());
				}
			}
		}
		return children;
	}

	private void resolveArtifact(Artifact artifact) throws ArtifactResolutionException
	{
		ArtifactRequest request = new ArtifactRequest();
		request.setRepositories(session.getCurrentProject().getRemoteProjectRepositories());
		request.setArtifact(RepositoryUtils.toArtifact(artifact));

		ArtifactResult artifactResult =
			repositorySystem.resolveArtifact(session.getRepositorySession(), request);

		artifact.setFile(artifactResult.getArtifact().getFile());
		artifact.setVersion(artifactResult.getArtifact().getVersion());
		artifact.setResolved(true);
	}

	protected void handleArtifacts(Set<Artifact> artifacts) throws EnforcerRuleException
	{
		List<IgnorableDependency> ignorableDependencies = new ArrayList<>();
		if (dependencies != null)
		{
			getLog().debug("BanClassUsage has ignorable dependencies");
			for (Dependency dependency : dependencies)
			{
				getLog().debug("Adding ignorable dependency: " + dependency);
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

		BannedClassMatcher matcher =
			new BannedClassMatcher(bannedClasses == null ? List.of() : bannedClasses);
		StopWatch sw = new StopWatch();
		sw.start();
		StringBuilder error = new StringBuilder();
		for (Artifact artifact : artifacts)
		{
			if (scopes != null && !scopes.contains(artifact.getScope()))
			{
				getLog().debug("Skipping " + artifact + " due to scope");
				continue;
			}

			getLog().debug("Analyzing artifact " + artifact);
			Set<String> banned = getBannedClasses(artifact, matcher, ignorableDependencies);
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

	protected static String asRegex(String wildcard)
	{
		StringBuilder result = new StringBuilder(wildcard.length());
		result.append('^');
		for (int index = 0; index < wildcard.length(); index++)
		{
			char character = wildcard.charAt(index);
			switch (character)
			{
				case '*':
					result.append(".*");
					break;
				case '?':
					result.append(".");
					break;
				case '$':
				case '(':
				case ')':
				case '.':
				case '[':
				case '\\':
				case ']':
				case '^':
				case '{':
				case '|':
				case '}':
					result.append("\\");
				default:
					result.append(character);
					break;
			}
		}
		result.append("(\\.class)?");
		result.append('$');
		return result.toString();
	}

	private Set<String> getBannedClasses(Artifact artifact, BannedClassMatcher matcher,
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
		JarScan scan = getJarScan(file, matcher);
		scan.warnings.forEach(getLog()::warn);
		scan.bannedReferences.stream()
			.filter(classname -> !isIgnored(artifact, classname, ignorableDependencies))
			.forEach(ret::add);
		return ret;
	}

	/**
	 * Every module of a reactor build checks mostly the same jars, so each jar is scanned once per
	 * build. The scans are kept in the repository session, which lives exactly as long as the build.
	 */
	private JarScan getJarScan(File file, BannedClassMatcher matcher) throws EnforcerRuleException
	{
		ConcurrentMap<List<Object>, CompletableFuture<JarScan>> scans = getJarScans();
		List<Object> key = List.of(file.getAbsolutePath(), file.length(), file.lastModified(),
			matcher.getBannedClasses());
		CompletableFuture<JarScan> scan = new CompletableFuture<>();
		CompletableFuture<JarScan> existing = scans.putIfAbsent(key, scan);
		if (existing != null)
		{
			scan = existing;
		}
		else
		{
			try
			{
				scan.complete(scanJar(file, matcher));
			}
			catch (EnforcerRuleException | RuntimeException e)
			{
				scans.remove(key, scan);
				scan.completeExceptionally(e);
			}
		}
		try
		{
			return scan.join();
		}
		catch (CompletionException e)
		{
			if (e.getCause() instanceof EnforcerRuleException)
				throw (EnforcerRuleException) e.getCause();
			throw e;
		}
	}

	@SuppressWarnings("unchecked")
	private ConcurrentMap<List<Object>, CompletableFuture<JarScan>> getJarScans()
	{
		SessionData data = session.getRepositorySession().getData();
		data.set(JarScan.class, null, new ConcurrentHashMap<>());
		return (ConcurrentMap<List<Object>, CompletableFuture<JarScan>>) data.get(JarScan.class);
	}

	private JarScan scanJar(File file, BannedClassMatcher matcher) throws EnforcerRuleException
	{
		Set<String> bannedReferences = new HashSet<>();
		List<String> warnings = new ArrayList<>();
		try (JarFile jarFile = new JarFile(file))
		{
			getLog().debug(file.getName() + " => " + file.getPath());
			for (JarEntry entry : Collections.list(jarFile.entries()))
			{
				if (!entry.isDirectory() && entry.getName().endsWith(".class"))
				{
					try (InputStream is = jarFile.getInputStream(entry))
					{
						getLog().debug("Checking " + entry.getName());
						byte[] classFile = is.readAllBytes();
						ClassReader reader = new ClassReader(classFile);
						if (!matcher.mayReferenceBannedClass(classFile, CONSTANT_POOL_START,
							reader.header))
							continue;
						ClassDependencyCollector collector = new ClassDependencyCollector();
						reader.accept(collector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
						collector.getDeps()
							.stream()
							.map(classname -> classname.replace("/", "."))
							.filter(matcher::matches)
							.forEach(bannedReferences::add);
					}
					catch (Exception e)
					{
						warnings.add("Skipping " + entry.getName() + " due to " + e.getMessage());
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
		return new JarScan(bannedReferences, warnings);
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

	private static final class JarScan
	{
		private final Set<String> bannedReferences;

		private final List<String> warnings;

		private JarScan(Set<String> bannedReferences, List<String> warnings)
		{
			this.bannedReferences = Set.copyOf(bannedReferences);
			this.warnings = List.copyOf(warnings);
		}
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
				getLog().debug(prefix + "Adding ignore: " + ignore);
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
				&& (classifier == null
					|| classifier.matcher(Objects.toString(dup.getClassifier(), "")).matches())
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
