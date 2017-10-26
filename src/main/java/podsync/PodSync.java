package podsync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import us.bpsm.edn.Keyword;
import us.bpsm.edn.parser.Parseable;
import us.bpsm.edn.parser.Parser;
import us.bpsm.edn.parser.Parsers;

public class PodSync {

	private final Map config;

	/**
	 * @param args
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException {

		InputStream is = PodSync.class.getResourceAsStream("/config.edn");
		Parseable parseable = Parsers.newParseable(new InputStreamReader(is, StandardCharsets.UTF_8));
		Parser parser = Parsers.newParser(Parsers.defaultConfiguration());
		Map config = (Map) parser.nextValue(parseable);
		PodSync ps = new PodSync(config);

		ps.run();
	}

	PodSync(Map config) throws IOException {
		this.config = config;
	}

	public void run() {
		((List) config.get(Keyword.newKeyword(":podcasts"))).forEach(p -> processPodcast(((Map)p)));
	}

	private void processPodcast(Map podcast) {

		try {
			URL rss = new URL((String) podcast.get(Keyword.newKeyword(":url")));
		} catch (MalformedURLException e1) {
			e1.printStackTrace();
		}

		File fromDir = new File((String) podcast.get(Keyword.newKeyword(":fromDir")));
		File toDir = new File((String) podcast.get(Keyword.newKeyword(":toDir")));

		if(toDir.isDirectory() != true) {
			System.out.println("toDir is not a directory: " + toDir);
			return;
		}

		if(fromDir.isDirectory() != true) {
			System.out.println("fromDir is not a directory: "+ fromDir);
			return;
		}

		// get youngest file from toDir
		File[] toDirFiles = toDir.listFiles();
		File[] fromDirFiles = fromDir.listFiles();
		Comparator<File> fc = new Comparator<File>() {
			public int compare(File f2, File f1)
			{
				return Long.valueOf(f1.lastModified()).compareTo(f2.lastModified());
			}
		};

		// sort files by time descending
		Arrays.sort(toDirFiles, fc);

		// sort files by time
		Arrays.sort(fromDirFiles, fc);

		//toDir empty?
		if(toDirFiles.length==0) {
			// copy newest fromDirFile to toDir
			if(fromDirFiles.length > 0) {
				File cf = fromDirFiles[0];
				try {
					if(!cf.isHidden())
						copyFile(cf,toDir);
				} catch (Exception e) {
					e.printStackTrace();
					return;
				}

			}
		} else {
			// copy all files from fromDir newer than newest file in toDir
			long newestFileMod = toDirFiles[0].lastModified();

			// FIXME: reverse loop!
			for(int i=0; i<fromDirFiles.length;i++) {
				File cf=fromDirFiles[i];
				if(cf.isHidden())
					continue;
				//System.out.println("fromDir time " + String.valueOf(cf.lastModified()));
				//System.out.println("toDir time " + String.valueOf(newestToDir));
				Long currentFileMod = Long.valueOf(cf.lastModified());

				// FAT fs seems to be not exact
				if (newestFileMod + 1000 == currentFileMod)
					newestFileMod = newestFileMod + 1000;

				if(currentFileMod.compareTo(newestFileMod) > 0) {
					try {
						System.out.println("newestFileMod= " + newestFileMod + " currentFileMod= " + currentFileMod);
						copyFile(cf,toDir);
					} catch (Exception e) {
						e.printStackTrace();
						return;
					}
				}
			}
		}
	}

	private void copyFile(File fromFile, File toDir) throws IOException {
		File toFile = new File(toDir.getAbsolutePath() + File.separator + fromFile.getName());

		try(BufferedInputStream bis = new BufferedInputStream(new FileInputStream(fromFile));
			BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(toFile));) {

			System.out.print("copy \"" + fromFile.getAbsolutePath() + "\"");
			System.out.println(" to \"" + toFile.getAbsolutePath() + "\"");

			int b = bis.read();
			while(b != -1) {
				bos.write(b);
				b = bis.read();
			}
		} finally {
			toFile.setLastModified(fromFile.lastModified());
		}
	}
}

