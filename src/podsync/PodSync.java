package podsync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Properties;

public class PodSync {

	private Properties props = new Properties();
	private File fnInput;

	PodSync(File propsFile) throws IOException {
		fnInput=propsFile;
		props.load(new BufferedReader(new FileReader(fnInput)));
	}

	public void clear() {
		props.clear();
		storeProps();
	}
	public void add (String key, String value) {
		props.setProperty(key, value);
		storeProps();
	}

	public void run() {

		// run
		File fromDir;
		File toDir;
		for(Object key: props.keySet()) {
			if(! (key instanceof String))
				continue;
			fromDir = new File((String)key);
			toDir = new File(props.getProperty((String) key));
			
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
						// TODO Auto-generated catch block
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
							// TODO Auto-generated catch block
							e.printStackTrace();
							return;
						}
					}
	
				}
			}
		}
	
	}

	private void storeProps() {
		String comments = "PodSync directories to sync";
		try {
			Writer out = new BufferedWriter(new FileWriter(fnInput));
			props.store(out, comments);
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
	}

	private void copyFile(File fromFile, File toDir) throws Exception {
		File toFile = new File(toDir.getAbsolutePath() + File.separator + fromFile.getName());
		
		BufferedInputStream bis = new BufferedInputStream(new FileInputStream(fromFile));
		BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(toFile));
		
		System.out.print("copy \"" + fromFile.getAbsolutePath() + "\"");
		System.out.println(" to \"" + toFile.getAbsolutePath() + "\"");
		
		int b = bis.read();
		
		while(b != -1) {
			bos.write(b);
			b = bis.read();
		}
		
		bis.close();
		bos.close();
		toFile.setLastModified(fromFile.lastModified());
		
	}

	public void list() {
		for(Object key: props.keySet()) {
			if(! (key instanceof String))
				continue;
			String keys = (String) key;
			String value = props.getProperty(keys);
			
			System.out.println("fromDir= " + keys + " toDir= " + value);
		}
	}
}

