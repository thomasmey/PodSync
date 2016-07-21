package podsync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.StringTokenizer;

public class Main {

	/**
	 * @param args
	 */
	public static void main(String[] args) {

		String fnInput;
		if(args.length == 0)
			fnInput = "sync-list.txt";
		else
			fnInput = args[0];

		String cl;
		BufferedReader br;
		try {
			br = new BufferedReader(new FileReader(fnInput));
			cl = br.readLine();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return;
		}
		File fromDir;
		File toDir;

		while(cl != null) {
			
			fromDir=null; toDir=null;
			StringTokenizer st = new StringTokenizer(cl,",");
			if(st.hasMoreTokens())
				fromDir = new File(st.nextToken());
			if(st.hasMoreTokens())
				toDir = new File(st.nextToken());
			
			if(toDir == null || fromDir == null) {
				System.out.println("toDir or fromDir not set!");
				return;
			}
			
			if(toDir.isDirectory() != true) {
				System.out.println("toDir is not a directory");
				return;
			}

			if(fromDir.isDirectory() != true) {
				System.out.println("fromDir is not a directory");
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
					try {
						copyFile(fromDirFiles[0],toDir);
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

			// read next dir to sync
			try {
				cl = br.readLine();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return;
			}
		}

	}

	private static void copyFile(File fromFile, File toDir) throws Exception {
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

}
