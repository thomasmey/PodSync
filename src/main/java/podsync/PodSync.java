package podsync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
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

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
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

		Map rssState = new HashMap<>();
		File rsf = new File((String) config.get(Keyword.newKeyword("rssState")));
		if(rsf.exists() && rsf.isFile()) {
			Parseable rsp = Parsers.newParseable(new FileReader(rsf));
			rssState = (Map) parser.nextValue(rsp);
		}
		PodSync ps = new PodSync(config, rssState);
		ps.run();
	}

	PodSync(Map config, Map rssState) throws IOException {
		this.config = config;
	}

	public void run() {
		((List) config.get(Keyword.newKeyword("podcasts"))).parallelStream().forEach(p -> processPodcast(((Map)p)));
	}

	private void processPodcast(Map podcast) {

		List<Map> items = new ArrayList<>();
		try {
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
					currentValue = "";
				}
				@Override
				public void characters(char[] ch, int start, int length) throws SAXException {
					currentValue += new String(ch, start, length);
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
								currentItem.put("length", Long.valueOf(currentAttributes.getValue("length").trim()));
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
								log.log(Level.SEVERE, "failed!", e);
							}
						}
						break;
					}
				}
			};

			Client client = ClientBuilder.newClient();

			URL rss = new URL((String) podcast.get(Keyword.newKeyword("url")));
			String username = (String) podcast.get(Keyword.newKeyword("username"));
			String password = (String) podcast.get(Keyword.newKeyword("password"));
			String etag = null;
			if(username != null && password != null) {
				client.register(HttpAuthenticationFeature.basic(username, password));
			}
			URI uri = rss.toURI();
			loop:
				for(;;) {
					log.log(Level.INFO, "Fetching RSS feed from {0}", uri);
					try(Response r = client.target(uri).request().header(HttpHeaders.IF_NONE_MATCH, etag).head()) {
						log.log(Level.INFO, "result {0}", r.getStatus());
						switch(Response.Status.fromStatusCode(r.getStatus())) {
						case OK:
							try(Response rd = client.target(uri).request().header(HttpHeaders.IF_NONE_MATCH, etag).get()) {
								if(rd.getStatus() == Response.Status.OK.getStatusCode()) {
									EntityTag entityTag = rd.getEntityTag();
									InputStream in = (InputStream) rd.getEntity();
									SAXParserFactory.newInstance().newSAXParser().parse(in, handler);
									break loop;
								}
							}
							break;
						case MOVED_PERMANENTLY:
							//TODO: update config with new URI
						case FOUND:
							uri = r.getLocation();
							log.log(Level.INFO, "redirect to {0}", uri);
							continue loop;
						case NOT_MODIFIED:
							return;
						}
					}
				}
		} catch (IOException | SAXException | ParserConfigurationException | URISyntaxException e1) {
			e1.printStackTrace();
		}

		Collections.sort(items, (i1, i2) -> ((Date)i2.get("pubDate")).compareTo((Date) i1.get("pubDate")));

		File fromDir = new File((String) podcast.get(Keyword.newKeyword("fromDir")));

		// check input
		if(fromDir.isDirectory() != true) {
			log.log(Level.SEVERE, "fromDir is not a directory: {0}", fromDir);
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
				HttpURLConnection uc = followRedirects(url);
				try(Response r = ClientBuilder.newClient().target(url.toURI()).request().head()) {
					MultivaluedMap<String, Object> headers = r.getHeaders();
					copy(uc.getInputStream(), new FileOutputStream(new File(fromDir, targetName)));
				} catch (IOException | URISyntaxException e) {
					e.printStackTrace();
				}
			}
		}

		// when toDir doesn't exist, only download
		String td = (String) podcast.get(Keyword.newKeyword("toDir"));
		if(td == null)
			return;

		File toDir = new File(td);
		if(toDir.isDirectory() != true) {
			log.log(Level.SEVERE, "toDir is not a directory: {0}", toDir);
			return;
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

	private static HttpURLConnection followRedirects(URL url) {
		try {
			HttpURLConnection.setFollowRedirects(true);
			HttpURLConnection uc;
			while(true) {
				uc = (HttpURLConnection) url.openConnection();
				int rc = uc.getResponseCode();
				switch(rc) {
				case HttpURLConnection.HTTP_MOVED_PERM:
				case HttpURLConnection.HTTP_MOVED_TEMP:
					String location = uc.getHeaderField("Location");
					URL next = new URL(url, location);
					url = next;
					break;
				case HttpURLConnection.HTTP_OK:
					return uc;
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		throw new IllegalStateException("couldn't retrieve URL " + url);
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

