/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.launch.knot;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.launch.common.FabricLauncherBase;
import net.fabricmc.loader.launch.common.FabricMixinBootstrap;
import net.fabricmc.loader.launch.common.MixinLoader;
import net.fabricmc.loader.transformer.FabricTransformer;
import net.fabricmc.loader.util.UrlConversionException;
import net.fabricmc.loader.util.UrlUtil;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.MixinTransformer;
import org.spongepowered.asm.util.Constants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public final class Knot extends FabricLauncherBase {
	protected Map<String, Object> properties = new HashMap<>();

	private PatchingClassLoader loader;
	private boolean isDevelopment;
	private EnvType envType;
	private String entryPoint;
	private File gameJarFile;
	private List<URL> classpath;

	private static class NullClassLoader extends ClassLoader {
		private static final Enumeration<URL> NULL_ENUMERATION = new Enumeration<URL>() {
			@Override
			public boolean hasMoreElements() {
				return false;
			}

			@Override
			public URL nextElement() {
				return null;
			}
		};

		static {
			registerAsParallelCapable();
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			throw new ClassNotFoundException(name);
		}

		@Override
		public URL getResource(String name) {
			return null;
		}

		@Override
		public Enumeration<URL> getResources(String var1) throws IOException {
			return NULL_ENUMERATION;
		}
	}

	private static class DynamicURLClassLoader extends URLClassLoader {
		public DynamicURLClassLoader(URL[] urls) {
			super(urls, new NullClassLoader());
		}

		public void addURL(URL url) {
			super.addURL(url);
		}

		static {
			registerAsParallelCapable();
		}
	}

	private static class PatchingClassLoader extends ClassLoader {
		private final DynamicURLClassLoader urlLoader;
		private final ClassLoader originalLoader;
		private final boolean isDevelopment;
		private final EnvType envType;
		private MixinTransformer mixinTransformer;

		public PatchingClassLoader(boolean isDevelopment, EnvType envType) {
			super(new DynamicURLClassLoader(new URL[0]));
			this.originalLoader = getClass().getClassLoader();
			this.urlLoader = (DynamicURLClassLoader) getParent();
			this.isDevelopment = isDevelopment;
			this.envType = envType;
		}

		public boolean isClassLoaded(String name) {
			synchronized (getClassLoadingLock(name)) {
				return findLoadedClass(name) != null;
			}
		}

		@Override
		public URL getResource(String name) {
			Objects.requireNonNull(name);

			URL url = urlLoader.getResource(name);
			if (url == null) {
				url = originalLoader.getResource(name);
			}
			return url;
		}

		@Override
		public InputStream getResourceAsStream(String var1) {
			return super.getResourceAsStream(var1);
		}

		@Override
		public Enumeration<URL> getResources(String name) throws IOException {
			Objects.requireNonNull(name);

			Enumeration<URL> first = urlLoader.getResources(name);
			Enumeration<URL> second = originalLoader.getResources(name);
			return new Enumeration<URL>() {
				Enumeration<URL> current = first;

				@Override
				public boolean hasMoreElements() {
					return current != null && current.hasMoreElements();
				}

				@Override
				public URL nextElement() {
					if (current == null) {
						return null;
					}

					if (!current.hasMoreElements()) {
						if (current == first) {
							current = second;
						} else {
							current = null;
							return null;
						}
					}

					return current.nextElement();
				}
			};
		}

		private MixinTransformer getMixinTransformer() {
			if (mixinTransformer == null) {
				try {
					Constructor<MixinTransformer> constructor = MixinTransformer.class.getDeclaredConstructor();
					constructor.setAccessible(true);
					mixinTransformer = constructor.newInstance();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}

			return mixinTransformer;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				Class<?> c = findLoadedClass(name);

				if (c == null) {
					if (!"net.fabricmc.api.EnvType".equals(name) && !"net.fabricmc.api.loader.Loader".equals(name) && !name.startsWith("net.fabricmc.loader.launch.") && /* MixinLoader -> */ !name.startsWith("org.apache.logging.log4j")) {
						byte[] b = null;

						// handle runtime-generated mixin packages
						if (name.startsWith(Constants.SYNTHETIC_PACKAGE + ".")) {
							b = getMixinTransformer().transformClassBytes(name, name, null);
						} else {
							byte[] input;
							try {
								input = getClassByteArray(name, true);
							} catch (IOException e) {
								throw new RuntimeException(e);
							}

							if (input != null) {
								if (name.indexOf('.') < 0) {
									throw new ClassNotFoundException("Root packages forbidden: class '" + name + "' could not be loaded");
								}

								b = FabricTransformer.transform(isDevelopment, envType, name, input);
								b = getMixinTransformer().transformClassBytes(name, name, b);
							}
						}

						if (b != null) {
							c = defineClass(name, b, 0, b.length);
						}
					}
				}

				if (c == null) {
					c = originalLoader.loadClass(name);
				}

				if (resolve) {
					resolveClass(c);
				}

				return c;
			}
        }

		public void addURL(URL url) {
			urlLoader.addURL(url);
		}

		static {
			registerAsParallelCapable();
		}

		public byte[] getClassByteArray(String name, boolean skipOriginalLoader) throws IOException {
			String classFile = name.replace('.', '/') + ".class";
			InputStream inputStream = urlLoader.getResourceAsStream(classFile);
			if (inputStream == null && !skipOriginalLoader) {
				inputStream = originalLoader.getResourceAsStream(classFile);
			}
			if (inputStream == null) {
				return null;
			}

			int a = inputStream.available();
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream(a < 32 ? 32768 : a);
			byte[] buffer = new byte[8192];
			int len;
			while ((len = inputStream.read(buffer)) > 0) {
				outputStream.write(buffer, 0, len);
			}

			inputStream.close();
			return outputStream.toByteArray();
		}
	}

	protected Knot(EnvType type, File gameJarFile) {
		this.envType = type;
		this.gameJarFile = gameJarFile;
	}

	protected void init(String[] args) {
		setProperties(properties);

		// parse args
		Map<String, String> argMap = new LinkedHashMap<>();
		List<String> extraArgs = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg.startsWith("--") && i < args.length - 1) {
				argMap.put(arg, args[++i]);
			} else {
				extraArgs.add(args[i]);
			}
		}

		// configure fabric vars
		if (envType == null) {
			String side = System.getProperty("fabric.side");
			if (side == null) {
				throw new RuntimeException("Please specify side or use a dedicated Knot!");
			}

			side = side.toLowerCase();
			if ("client".equals(side)) {
				envType = EnvType.CLIENT;
			} else if ("server".equals(side)) {
				envType = EnvType.SERVER;
			} else {
				throw new RuntimeException("Invalid side provided: must be \"client\" or \"server\"!");
			}
		}

		FabricLauncherBase.processArgumentMap(argMap, envType);
		String[] newArgs = FabricLauncherBase.asStringArray(argMap, extraArgs);

		isDevelopment = Boolean.parseBoolean(System.getProperty("fabric.development", "false"));
		entryPoint = envType == EnvType.CLIENT ? "net.minecraft.client.main.Main" : "net.minecraft.server.MinecraftServer";

		// Setup classloader
		loader = new PatchingClassLoader(isDevelopment(), envType);
		String[] classpathStringsIn = System.getProperty("java.class.path").split(File.pathSeparator);
		List<String> classpathStrings = new ArrayList<>(classpathStringsIn.length);

		for (String s : classpathStringsIn) {
			if (s.equals("*") || s.endsWith(File.separator + "*")) {
				System.err.println("WARNING: Knot does not support wildcard classpath entries: " + s + " - the game may not load properly!");
			} else {
				classpathStrings.add(s);
			}
		}

		classpath = new ArrayList<>(classpathStrings.size());
		populateClasspath(argMap, classpathStrings);

		// Add loader to classpath - this is necessary so that net.fabricmc.loader gets
		// loaded in the correct location.
		URL loaderUrl = getClass().getProtectionDomain().getCodeSource().getLocation();
		propose(loaderUrl);
		if (!classpath.contains(loaderUrl)) {
			classpath.add(loaderUrl);
		}

		Thread.currentThread().setContextClassLoader(loader);

		// Setup Mixin environment
		MixinLoader mixinLoader = new MixinLoader();
		mixinLoader.load(new File(getLaunchDirectory(argMap), "mods"));
		mixinLoader.freeze();

		MixinBootstrap.init();
		FabricMixinBootstrap.init(getEnvironmentType(), argMap, mixinLoader);
		MixinEnvironment.getDefaultEnvironment().setSide(envType == EnvType.CLIENT ? MixinEnvironment.Side.CLIENT : MixinEnvironment.Side.SERVER);

		FabricLauncherBase.pretendMixinPhases();

		try {
			Class c = loader.loadClass(entryPoint);
			Method m = c.getMethod("main", String[].class);
			m.invoke(null, (Object) newArgs);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void populateClasspath(Map<String, String> argMap, Collection<String> classpathStrings) {
		String entryPointFilename = entryPoint.replace('.', '/') + ".class";
		File gameFile = this.gameJarFile;

		if (gameFile == null) {
			for (String filename : classpathStrings) {
				File file = new File(filename);
				boolean hasGame = false;

				if (file.isDirectory()) {
					hasGame = new File(gameFile, entryPointFilename).exists();
				} else if (file.isFile()) {
					try {
						JarFile jf = new JarFile(file);
						ZipEntry entry = jf.getEntry(entryPointFilename);
						hasGame = entry != null;
					} catch (IOException e) {
						// pass
					}
				}

				if (hasGame) {
					if (gameFile != null && !gameFile.equals(file)) {
						throw new RuntimeException("Found duplicate game instances: [" + gameFile + ", " + file + "]");
					}
					gameFile = file;
				} else {
					try {
						classpath.add(UrlUtil.asUrl(file));
					} catch (UrlConversionException e) {
						e.printStackTrace();
					}
				}
			}
		} else {
			for (String filename : classpathStrings) {
				File file = new File(filename);
				if (!file.equals(gameFile)) {
					try {
						classpath.add(UrlUtil.asUrl(file));
					} catch (UrlConversionException e) {
						e.printStackTrace();
					}
				}
			}
		}

		if (gameFile == null) {
			throw new RuntimeException("Entrypoint '" + entryPoint + "' not found!");
		}

		FabricLauncherBase.deobfuscate(
			getLaunchDirectory(argMap),
			gameFile,
			this
		);
	}

	@Override
	public void propose(URL url) {
		loader.addURL(url);
	}

	@Override
	public Collection<URL> getClasspathURLs() {
		return Collections.unmodifiableList(classpath);
	}

	@Override
	public EnvType getEnvironmentType() {
		return envType;
	}

	@Override
	public boolean isClassLoaded(String name) {
		return loader.isClassLoaded(name);
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		return loader.getResourceAsStream(name);
	}

	@Override
	public ClassLoader getTargetClassLoader() {
		return loader;
	}

	@Override
	public byte[] getClassByteArray(String name) throws IOException {
		return loader.getClassByteArray(name, false);
	}

	@Override
	public boolean isDevelopment() {
		return isDevelopment;
	}

	public static void main(String[] args) {
		new Knot(null, null).init(args);
	}
}
