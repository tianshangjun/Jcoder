package org.nlpcn.jcoder.controller;

import org.nlpcn.jcoder.domain.FileInfo;
import org.nlpcn.jcoder.domain.Task;
import org.nlpcn.jcoder.filter.AuthoritiesManager;
import org.nlpcn.jcoder.run.java.JavaSourceUtil;
import org.nlpcn.jcoder.service.JarService;
import org.nlpcn.jcoder.util.*;
import org.nutz.dao.Cnd;
import org.nutz.ioc.loader.annotation.IocBean;
import org.nutz.lang.Streams;
import org.nutz.mvc.annotation.*;
import org.nutz.mvc.upload.TempFile;
import org.nutz.mvc.upload.UploadAdaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Created by Ansj on 19/12/2017.
 * 这个类提供集群间文件交换下载
 */

@IocBean
@Filters(@By(type = AuthoritiesManager.class))
@At("/admin/fileInfo")
@Ok("json")
public class FileInfoAction {

	private static final Logger LOG = LoggerFactory.getLogger(FileInfoAction.class);

	/**
	 * 获取文件列表
	 *
	 * @param groupName
	 * @return
	 * @throws IOException
	 */
	@At
	public Restful listFiles(@Param("groupName") String groupName) throws IOException {

		List<FileInfo> result = new ArrayList<>();

		Path path = new File(StaticValue.GROUP_FILE, groupName).toPath();

		Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
			// 在访问子目录前触发该方法
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				File file = dir.toFile();
				if (!file.canRead() || file.isHidden() || file.getName().charAt(0) == '.') {
					LOG.warn(path.toString() + " is hidden or can not read or start whth '.' so skip it ");
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
				File file = path.toFile();
				if (!file.canRead() || file.isHidden() || file.getName().charAt(0) == '.') {
					LOG.warn(path.toString() + " is hidden or can not read or start whth '.' so skip it ");
					return FileVisitResult.CONTINUE;
				}
				try {
					result.add(new FileInfo(file));
				} catch (Exception e) {
					e.printStackTrace();
				}
				return FileVisitResult.CONTINUE;
			}
		});

		return Restful.instance().obj(result);
	}

	/**
	 * 获得一个文件的输出流
	 *
	 * @param relativePath 抽象路径
	 */
	@At
	@Ok("void")
	public void downFile(@Param("groupName") String groupName, @Param("relativePath") String relativePath, HttpServletResponse response) throws IOException {

		if (relativePath.contains("..")) {
			throw new FileNotFoundException("下载路径不能包含`..`字符");
		}

		File file = new File(StaticValue.GROUP_FILE, groupName + relativePath);

		if (!file.exists()) {
			throw new FileNotFoundException(file.toURI().getPath() + " not found in " + StaticValue.getHostPort());
		}

		response.setContentType("application/octet-stream");

		if (file.isDirectory()) {
			response.addHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(file.getName(), "utf-8") + ".zip");
			try (ZipOutputStream out = new ZipOutputStream(response.getOutputStream())) {
				Files.walkFileTree(file.toPath(), new SimpleFileVisitor<Path>() {

					@Override
					public FileVisitResult visitFile(Path tempFile, BasicFileAttributes attrs) throws IOException {
						File f = tempFile.toFile();
						out.putNextEntry(new ZipEntry(f.getAbsolutePath().replace(file.getAbsolutePath(), "")));
						int len = 0;
						byte[] buffer = new byte[10240];
						if (!f.isDirectory() && f.canRead()) {
							try (FileInputStream fis = new FileInputStream(f)) {
								while ((len = fis.read(buffer)) > 0) {
									out.write(buffer, 0, len);
								}
							}
						}
						return FileVisitResult.CONTINUE;
					}
				});
			}
		} else {
			response.setContentLength((int) file.length());
			response.addHeader("Content-Disposition", "attachment;filename=" + file.getName());
			ServletOutputStream outputStream = response.getOutputStream();

			try (FileInputStream fis = new FileInputStream(file)) {
				Streams.write(outputStream, fis);
			}
		}
	}

	/**
	 * 删除一个文件或文件夹
	 *
	 * @param groupName
	 * @param relativePath
	 * @return
	 */
	@At
	public Restful delete(@Param("groupName") String groupName, @Param("relativePath") String relativePath) {
		if (relativePath.contains("..")) {
			return Restful.instance(false, "删除路径不能包含`..`字符");
		}
		File file = new File(StaticValue.GROUP_FILE, groupName + relativePath);
		if (file.isDirectory()) {
			org.nutz.lang.Files.deleteDir(file);
		} else {
			org.nutz.lang.Files.deleteFile(file);
		}
		return Restful.OK;
	}


	@At
	@AdaptBy(type = UploadAdaptor.class)
	public Restful upload(@Param("groupName") String groupName, @Param("relativePath") String relativePath, @Param("relativePath") TempFile[] fileList) throws IOException {

		if (relativePath.contains("..")) {
			return Restful.instance(false, "上传路径不能包含`..`字符");
		}

		File file = null;

		if (StringUtil.isBlank(relativePath) || "/".equals(relativePath)) {
			file = new File(StaticValue.GROUP_FILE, groupName);
		} else {
			file = new File(StaticValue.GROUP_FILE, groupName + relativePath);
		}


		for (TempFile tempFile : fileList) {
			try {
				File to = new File(file, tempFile.getSubmittedFileName());
				tempFile.write(to.getAbsolutePath());
				LOG.info("write file to " + to.getAbsolutePath());
			} catch (IOException e) {
				e.printStackTrace();
			}

		}

		return Restful.instance().ok(true).msg("上传成功");
	}


	/**
	 * 下载开发者工具
	 *
	 * @throws URISyntaxException
	 * @throws IOException
	 */
	@At
	@Ok("void")
	public void downSDK(@Param("groupName") String groupName, HttpServletResponse response) throws URISyntaxException, IOException {

		List<File> jars = new ArrayList<>();

		JarService jarService = JarService.getOrCreate(groupName);

		jars.addAll(jarService.findSystemJars());

		File jarPath = new File(StaticValue.GROUP_FILE, groupName + "/lib");


		Collection<Task> taskList = StaticValue.systemDao.search(Task.class, Cnd.where("status", "=", 1));

		byte[] buffer = new byte[10240];

		int len = 0;

		response.addHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(groupName,"utf-8") + ".zip");
		response.setContentType("application/octet-stream");

		try (ZipOutputStream out = new ZipOutputStream(response.getOutputStream())) {
			Set<String> sets = new HashSet<>();
			// 写jar包
			if (jarPath.exists() && jarPath.isDirectory()) {
				for (File jar : jarPath.listFiles()) {
					if (jar.isDirectory() || !jar.canRead() || !jar.getName().toLowerCase().endsWith(".jar")) {
						continue;
					}
					// skip maven jar
					if (jar.getParentFile().getAbsolutePath().startsWith(new File(StaticValue.GROUP_FILE, groupName + "/lib/target").getAbsolutePath())) {
						continue;
					}
					String name = groupName + "/lib/" + jar.getName();
					if (sets.contains(name)) {
						continue;
					}
					sets.add(name);
					out.putNextEntry(new ZipEntry(groupName + "/lib/" + jar.getName()));
					try (FileInputStream fis = new FileInputStream(jar)) {
						while ((len = fis.read(buffer)) > 0) {
							out.write(buffer, 0, len);
						}
					}
				}
			}
			out.putNextEntry(new ZipEntry("src/main/java/package-info.java"));
			out.write(("/**\n" + " * if you need make some jar file write in src package\n" + " */").getBytes());

			out.putNextEntry(new ZipEntry("src/test/java/package-info.java"));
			out.write(("/**\n" + " * if you need make some jar file write in src package\n" + " */").getBytes());

			out.putNextEntry(new ZipEntry("src/api/java/package-info.java"));
			out.write(("/**\n" + " * if you need make some jar file write in src package\n" + " */").getBytes());

			// 写task任务
			for (Task task : taskList) {
				try {
					String code = task.getCode();
					String path = JavaSourceUtil.findPackage(code);
					String className = JavaSourceUtil.findClassName(code);

					out.putNextEntry(new ZipEntry("src/api/java/" + path.replace(".", "/") + "/" + className + ".java"));
					out.write(code.getBytes("utf-8"));
				} catch (RuntimeException e) {
				}
			}

			out.putNextEntry(new ZipEntry("pom.xml"));
			out.write(IOUtil.getContent(new File(StaticValue.GROUP_FILE, groupName + "/lib/pom.xml"), "utf-8").getBytes("utf-8"));

			File file = new File(StaticValue.GROUP_FILE, groupName + "/resources");
			String basePath = file.getAbsolutePath();
			Files.walkFileTree(file.toPath(), new SimpleFileVisitor<Path>() {

				@Override
				public FileVisitResult visitFile(Path tempFile, BasicFileAttributes attrs) throws IOException {

					File f = tempFile.toFile();

					if (f.isDirectory() || !f.canRead() || f.isHidden()) {
						return FileVisitResult.CONTINUE;
					}

					if (f.getParentFile().equals(file) && "pom.xml".equals(f.getName())) { // skip pom.xml
						return FileVisitResult.CONTINUE;
					}

					String filePath = ("src/test/resources/" + f.getAbsolutePath().replace(basePath, "")).replace("\\", "/").replace("//", "/");

					out.putNextEntry(new ZipEntry(filePath));

					int len = 0;
					byte[] buffer = new byte[10240];

					try (FileInputStream fis = new FileInputStream(f)) {
						while ((len = fis.read(buffer)) > 0) {
							out.write(buffer, 0, len);
						}
					}

					return FileVisitResult.CONTINUE;
				}
			});

		}

	}

}
