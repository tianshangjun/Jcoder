package org.nlpcn.jcoder.service;

import com.alibaba.fastjson.JSONObject;
import com.google.common.cache.*;
import org.nlpcn.jcoder.domain.FileInfo;
import org.nlpcn.jcoder.run.java.DynamicEngine;
import org.nlpcn.jcoder.scheduler.TaskException;
import org.nlpcn.jcoder.util.IOUtil;
import org.nlpcn.jcoder.util.MD5Util;
import org.nlpcn.jcoder.util.StaticValue;
import org.nlpcn.jcoder.util.StringUtil;
import org.nutz.ioc.Ioc;
import org.nutz.ioc.impl.NutIoc;
import org.nutz.ioc.loader.annotation.IocBean;
import org.nutz.ioc.loader.json.JsonLoader;
import org.nutz.lang.Lang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@IocBean
public class JarService {

	private static final Logger LOG = LoggerFactory.getLogger(JarService.class);

	private static final LoadingCache<String, JarService> CACHE = CacheBuilder.newBuilder()
			.removalListener((RemovalListener<String, JarService>) notification -> {
				try {
					notification.getValue().getIoc().depose();
				} catch (Exception e) {
					e.printStackTrace();
				}
				try {
					if (notification.getValue().engine != null) {
						notification.getValue().engine.close();
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}).build(new CacheLoader<String, JarService>() {
				@Override
				public JarService load(String key) throws Exception {
					return new JarService(key);
				}
			});

	public static JarService getOrCreate(String groupName) {
		try {
			return CACHE.get(groupName);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public static void remove(String groupName) {
		CACHE.invalidate(groupName);
	}


	private static final String MAVEN_PATH = "maven";
	private static final String MD5 = "md5";

	private String groupName;

	private String jarPath = null;

	private String pomPath = null;

	private String iocPath = null;

	public Set<String> libPaths = new HashSet<>();

	private DynamicEngine engine;

	private Ioc ioc;

	private JarService(String groupName) throws IOException {
		this.groupName = groupName;
		jarPath = new File(StaticValue.GROUP_FILE, "group/" + groupName + "/lib").getCanonicalPath();
		pomPath = jarPath + "/pom.xml";
		iocPath = new File(StaticValue.GROUP_FILE, "group/" + groupName + "/resource/ioc.js").getCanonicalPath();
		engine = new DynamicEngine(groupName);
		init();
	}

	/**
	 * 环境加载中
	 */
	public void init() {
		// 如果发生改变则刷新一次
		try {
			flushMaven();
			flushClassLoader();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 统计并加载jar包
	 */
	private void flushClassLoader() {
		LOG.info("to flush classloader");
		URL[] urls = null;
		try {
			List<File> findJars = findJars();
			urls = new URL[findJars.size()];
			libPaths.clear();
			for (int i = 0; i < findJars.size(); i++) {
				urls[i] = findJars.get(i).toURI().toURL();
				LOG.info("find JAR " + findJars.get(i));
				libPaths.add(findJars.get(i).getAbsolutePath());
			}
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		URLClassLoader classLoader = new URLClassLoader(urls, Thread.currentThread().getContextClassLoader().getParent()); //不在和系统的classload共享jar包，用来解决jar冲突

		try {
			engine.flush(classLoader);
			flushIOC();
		} catch (TaskException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	/**
	 * 保存ioc文件
	 *
	 * @param code
	 * @return
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 */
	public void saveIoc(String groupName, String code) throws IOException, NoSuchAlgorithmException {
		File ioc = new File(StaticValue.GROUP_FILE, groupName + "/resources");
		IOUtil.Writer(new File(ioc, "ioc.js").getAbsolutePath(), "utf-8", code);
		flushIOC();
	}

	private synchronized void flushIOC() {
		LOG.info("to flush ioc");

		JsonLoader loader = null;

		if (!new File(iocPath).exists()) {
			LOG.warn("iocPath: {} not exists so create an empty ioc!!!!!!!");
			loader = new JsonLoader();
		} else {
			loader = new JsonLoader(iocPath);
		}
		ioc = new NutIoc(loader);


		// 实例化lazy为false的bean
		loader.getMap().entrySet().stream()
				.filter(entry -> entry.getValue().containsKey("type") && Objects.equals(false, entry.getValue().get("lazy")))
				.forEach(entry -> {
					// 移除自定义配置项lazy
					entry.getValue().remove("lazy");

					LOG.info("to init bean[{}{}]", entry.getKey(), entry.getValue());

					//
					ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
					Thread.currentThread().setContextClassLoader(engine.getClassLoader());
					try {
						ioc.get(Lang.loadClass(entry.getValue().get("type").toString()), entry.getKey());
					} catch (ClassNotFoundException e) {
						throw Lang.wrapThrow(e);
					} finally {
						Thread.currentThread().setContextClassLoader(contextClassLoader);
					}
				});

	}

	/**
	 * 得到maven路径
	 *
	 * @return
	 */
	public String getMavenPath() {
		String mavenPath = null;

		if (StringUtil.isBlank(mavenPath)) {
			mavenPath = System.getProperty(StaticValue.PREFIX + "maven");
		}

		if (StringUtil.isBlank(mavenPath)) {
			String home = getPathByVar("MAVEN_HOME");
			if (StringUtil.isBlank(home)) {
				home = getPathByVar("M2_HOME");
			}
			if (StringUtil.isBlank(home)) {
				mavenPath = "mvn";
			} else {
				mavenPath = home + "/bin/mvn";
			}
		}

		return mavenPath;
	}

	/**
	 * copyjar包到当前目录中
	 *
	 * @return
	 * @throws IOException
	 */
	public String copy() throws IOException {
		if (System.getProperty("os.name").toLowerCase().contains("windows")) {
			return execute("cmd", "/c", getMavenPath(), "-f", "pom.xml", "dependency:copy-dependencies");
		} else {
			return execute(getMavenPath(), "-f", "pom.xml", "dependency:copy-dependencies");
		}
	}

	/**
	 * 删除jiar包
	 *
	 * @return
	 * @throws IOException
	 */
	public String clean() throws IOException {
		if (System.getProperty("os.name").toLowerCase().contains("windows")) {
			return execute("cmd", "/c", getMavenPath(), "clean");
		} else {
			return execute(getMavenPath(), "clean");
		}
	}

	/**
	 * 刷新jar包
	 *
	 * @return
	 * @throws IOException
	 */
	public synchronized void flushMaven() throws IOException {
		clean();
		copy();
	}

	private String execute(String... args) throws IOException {

		LOG.info("exec : " + Arrays.toString(args));

		StringBuilder sb = new StringBuilder();

		try {
			ProcessBuilder pb = new ProcessBuilder(args);
			pb.directory(new File(jarPath));

			pb.redirectErrorStream(true);

			/* Start the process */
			Process proc = pb.start();

			LOG.info("Process started !");

			/* Read the process's output */
			String line;
			BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
			while ((line = in.readLine()) != null) {
				sb.append(line).append("\n");
				LOG.info(line);
			}

			/* Clean-up */
			proc.destroy();
			LOG.info("Process ended !");
		} catch (Exception e) {
			LOG.warn("MAVEN_PATH ERR : " + e);
		}

		return sb.toString();
	}

	/**
	 * 查找所有的jar
	 *
	 * @return
	 * @throws IOException
	 */
	public List<File> findJars() throws IOException {
		List<File> findAllJar = new ArrayList<>();

		if (!new File(jarPath).exists()) {
			return findAllJar;
		}

		Files.walkFileTree(new File(jarPath).toPath(), new SimpleFileVisitor<Path>() {

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				File f = file.toFile();
				if (f.getName().toLowerCase().endsWith(".jar")) {
					findAllJar.add(f);
				}
				return FileVisitResult.CONTINUE;
			}
		});

		return findAllJar;
	}

	private String getPathByVar(String var) {
		String home = System.getProperty(var);

		if (StringUtil.isBlank(home)) {
			home = System.getenv("MAVEN_HOME");
		}
		return home;
	}

	/**
	 * 保存pom文件
	 *
	 * @param content
	 * @return
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 */
	public void savePomInfo(String groupName, String content) throws IOException, NoSuchAlgorithmException {
		File pom = new File(StaticValue.GROUP_FILE, groupName + "/lib");
		IOUtil.Writer(new File(pom, "pom.xml").getAbsolutePath(), "utf-8", content);
		flushMaven();
	}

	/**
	 * 获取pom文件内容
	 *
	 * @param groupName
	 * @return
	 * @throws Exception
	 */
	public String getPomInfo(String groupName) throws Exception {
		SharedSpaceService space = StaticValue.space();
		byte[] data2ZK = space.getData2ZK(space.GROUP_PATH + "/" + groupName + "/file/lib/pom.xml");
		if (data2ZK == null) return "";
		FileInfo fileInfo = JSONObject.parseObject(data2ZK, FileInfo.class);
		return fileInfo.getMd5();
	}

	/**
	 * 得到启动时候加载的路径
	 *
	 * @return
	 */
	public HashSet<String> getLibPathSet() {
		return new HashSet<>(libPaths);
	}

	/**
	 * 删除一个jar包.只能删除非maven得jar包
	 *
	 * @param file
	 * @return
	 */
	public boolean removeJar(File file) {
		if (file.getParentFile().getAbsolutePath().equals(new File(jarPath).getAbsolutePath()) && file.getPath().toLowerCase().endsWith(".jar")) {
			try {
				synchronized (this) {
					for (int i = 0; i < 10 && file.exists(); i++) {
						LOG.info(i + " to delete file: " + file.getAbsolutePath());
						file.delete();
						Thread.sleep(300L);
					}
					CACHE.invalidate(groupName);
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return true;
		} else {
			LOG.info(file.getAbsolutePath() + " is not manager by file JAR_PATH is :" + jarPath);
			return false;
		}

	}

	/**
	 * 获得系统中的jar.就是WEB-INF/lib下面的.对于eclipse中.取得SystemLoad
	 *
	 * @return
	 * @throws URISyntaxException
	 */
	public List<File> findSystemJars() throws URISyntaxException {

		URLClassLoader classLoader = ((URLClassLoader) Thread.currentThread().getContextClassLoader());

		URL[] urls = classLoader.getURLs();

		if (urls.length == 0) {
			classLoader = (URLClassLoader) classLoader.getParent();
			urls = classLoader.getURLs();
		}

		List<File> systemFiles = new ArrayList<>();

		for (URL url : urls) {
			if (url.toString().toLowerCase().endsWith(".jar")) {
				systemFiles.add(new File(url.toURI()));
			}
		}

		return systemFiles;
	}


	public Ioc getIoc() {
		return ioc;
	}

	public DynamicEngine getEngine() {
		return engine;
	}

	public String getIocPath() {
		return iocPath;
	}

	public String getPomPath() {
		return pomPath;
	}

	public String getJarPath() {
		return jarPath;
	}

	/**
	 * 释放和关闭当前jarservice。在操作。ioc和jar 之后。都需要调用此方式使之生效
	 */
	public void release() {
		CACHE.invalidate(groupName);
	}
}
