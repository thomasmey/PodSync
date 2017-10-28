package podsync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import us.bpsm.edn.Keyword;
import us.bpsm.edn.parser.Parseable;
import us.bpsm.edn.parser.Parser;
import us.bpsm.edn.parser.Parsers;

public class PodSync {

	private static Logger log = Logger.getLogger("PodSync");

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
		((List) config.get(Keyword.newKeyword("podcasts"))).parallelStream().forEach(p -> processPodcast(((Map)p)));
	}

	private void processPodcast(Map podcast) {

		List<Map> items = new ArrayList<>();
		try {
			URL rss = new URL((String) podcast.get(Keyword.newKeyword("url")));
			DefaultHandler handler = new DefaultHandler() {
				DateFormat rfc822 = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
				Map currentItem;
				String currentValue;
				Attributes currentAttributes;
				int currentLevel;

				@Override
				public void startElement(String arg0, String arg1, String qName, Attributes attributes) throws SAXException {
					if("item".equals(qName)) {
						currentItem = new HashMap<>();
					}
					currentAttributes = attributes;
					currentLevel++;
				}
				@Override
				public void characters(char[] ch, int start, int length) throws SAXException {
					currentValue = new String(ch, start, length);
				}
				@Override
				public void endElement(String arg0, String arg1, String qName) throws SAXException {
					currentLevel--;
					switch(qName) {
					case "item":
						items.add(currentItem);
						currentItem = null;
						break;
					case "title":
						if(currentItem != null) currentItem.put(qName, currentValue);
						break;
					case "enclosure":
						if(currentItem != null) {
							try {
								currentItem.put("url", new URL(currentAttributes.getValue("url")));
								currentItem.put("length", Integer.valueOf(currentAttributes.getValue("length")));
								currentItem.put("type", currentAttributes.getValue("type"));
							} catch (MalformedURLException e) {
							}
						}
						break;
					case "pubDate":
						if(currentItem != null) {
							try {
								Date d = rfc822.parse(currentValue);
								currentItem.put(qName, d);
							} catch (ParseException e) {
							}
						}
						break;
					}
				}
			};
			SAXParserFactory.newInstance().newSAXParser().parse(rss.openStream(), handler);
		} catch (IOException | SAXException | ParserConfigurationException e1) {
			e1.printStackTrace();
		}

		Collections.sort(items, (i1, i2) -> ((Date)i2.get("pubDate")).compareTo((Date) i1.get("pubDate")));

		File fromDir = new File((String) podcast.get(Keyword.newKeyword("fromDir")));
		File toDir = new File((String) podcast.get(Keyword.newKeyword("toDir")));

		// check input
		if(fromDir.isDirectory() != true) {
			log.log(Level.SEVERE, "fromDir is not a directory: {0}", fromDir);
			return;
		}
		if(toDir.isDirectory() != true) {
			log.log(Level.SEVERE, "toDir is not a directory: {0}", toDir);
			return;
		}

		File[] fromDirFiles = fromDir.listFiles();

		// check if we need to download the latest item
		if(items.size() > 0) {
			Map latestItem = items.get(0);
			URL url = (URL) latestItem.get("url");
			String[] segments = url.getPath().split("/");
			String targetName = segments[segments.length - 1];
			if(!Arrays.stream(fromDirFiles).anyMatch( f -> f.getName().equals(targetName))) {
				log.log(Level.INFO, "Downloading {0} from {1}", new Object[] {targetName, url});
				try {
					copy(url.openStream(), new FileOutputStream(new File(fromDir, targetName)));
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		fromDirFiles = fromDir.listFiles();
		File[] toDirFiles = toDir.listFiles();
		// get youngest file from toDir
		Comparator<File> fc = Comparator.comparing(File::lastModified);

		// sort files by time descending
		Arrays.sort(toDirFiles, fc);
		Arrays.sort(fromDirFiles, fc);

		//toDir empty?
		if(toDirFiles.length == 0) {
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
				log.log(Level.FINE, "fromDir time {0}", String.valueOf(cf.lastModified()));
//				log.log(Level.FINE, "toDir time {0}", String.valueOf(newestToDir));
				Long currentFileMod = Long.valueOf(cf.lastModified());

				// FAT fs seems to be not exact
				if (newestFileMod + 1000 == currentFileMod)
					newestFileMod = newestFileMod + 1000;

				if(currentFileMod.compareTo(newestFileMod) > 0) {
					try {
						log.log(Level.INFO, "newestFileMod= {0} currentFileMod= {1}", new Object[] {newestFileMod, currentFileMod});
						copyFile(cf,toDir);
					} catch (Exception e) {
						e.printStackTrace();
						return;
					}
				}
			}
		}
	}

	private static void copy(InputStream from, OutputStream to) throws IOException {
		try(BufferedInputStream bis = new BufferedInputStream(from);
			BufferedOutputStream bos = new BufferedOutputStream(to);) {
			int b = bis.read();
			while(b != -1) {
				bos.write(b);
				b = bis.read();
			}
		}
	}

	private static void copyFile(File fromFile, File toDir) throws IOException {
		File toFile = new File(toDir.getAbsolutePath() + File.separator + fromFile.getName());

		log.log(Level.INFO, "copy from \"{0}\" to \"{1}\"", new Object[] {fromFile.getAbsolutePath(), toFile.getAbsolutePath()});
		try {
			copy(new FileInputStream(fromFile), new FileOutputStream(toFile));
		} finally {
			toFile.setLastModified(fromFile.lastModified());
		}
	}
}

