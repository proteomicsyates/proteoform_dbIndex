package edu.scripps.yates.proteoform_dbindex;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import org.apache.log4j.Logger;

import edu.scripps.yates.dbindex.ProteinCache;
import edu.scripps.yates.proteoform_dbindex.model.ExtendedAssignMass;
import edu.scripps.yates.proteoform_dbindex.model.PTM;
import edu.scripps.yates.proteoform_dbindex.model.PTMCodeObj;
import edu.scripps.yates.utilities.fasta.dbindex.DBIndexStoreException;

public class ProteoformProteinCache extends ProteinCache {
	private final static Logger log = Logger.getLogger(ProteoformProteinCache.class);
	public static final String FILE_NAME = "proteinCache.txt";
	private final ExtendedAssignMass extendedAssignMass;
	private final File proteinCacheFile;
	private boolean loaded;
	private final static Charset CHARSET = StandardCharsets.UTF_8;
	private final List<Integer> indexesToWrite = new ArrayList<Integer>();
	private static final int bufferSize = 1000;
	private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

	public ProteoformProteinCache(ExtendedAssignMass extendedAssignMass, File proteinCacheFile) {
		this.extendedAssignMass = extendedAssignMass;
		this.proteinCacheFile = proteinCacheFile;
	}

	public String getPeptideSequence(int proteinId, int seqOffset, short seqLen, List<PTM> ptms)
			throws DBIndexStoreException {
		String protSeq = null;
		try {
			protSeq = getProteinSequence(proteinId);
		} catch (final Exception e) {
			e.printStackTrace();
			final String message = "Error trying to get protein from protein cache with index " + proteinId;
			log.error(message);
			log.error("Using beginIndex:" + seqOffset + " and length: " + seqLen);
			log.error(e.getMessage());
			throw new DBIndexStoreException(message, e);
		}

		try {
			String peptideSeq = null;
			try {
				peptideSeq = protSeq.substring(seqOffset, seqOffset + seqLen);
			} catch (final StringIndexOutOfBoundsException e) {
				e.printStackTrace();
			}
			return applyPTMs(peptideSeq, ptms);
		} catch (final Exception e) {
			e.printStackTrace();
			final String error = "Error tryin to get substring from " + protSeq + "\nUsing beginIndex:" + seqOffset
					+ " and length: " + seqLen + ": " + e.getMessage();
			log.error(error);
			throw new DBIndexStoreException(error, e);
		}

	}

	private String applyPTMs(String peptideSeq, List<PTM> ptms) {
		if (ptms == null || ptms.isEmpty()) {
			return peptideSeq;
		}
		final StringBuilder sb = new StringBuilder();
		final Map<Integer, PTM> ptmsByPosition = new HashMap<Integer, PTM>();
		for (final PTM ptm : ptms) {
			ptmsByPosition.put(ptm.getPosInPeptide(), ptm);
		}

		for (int pos = 1; pos <= peptideSeq.length(); pos++) {
			if (ptmsByPosition.containsKey(pos)) {
				final PTM ptm = ptmsByPosition.get(pos);
				final PTMCodeObj ptmCodeObj = extendedAssignMass.getPTMbyPTMCode(ptm.getPtmCode());
				if (!ptmCodeObj.getDescription().contains("->")) {
					sb.append(peptideSeq.charAt(pos - 1));
				}
				sb.append("[" + ptmCodeObj.getDescription() + "]");
				if (ptmCodeObj.getDescription().contains("->")) {
					final String original = ptmCodeObj.getDescription().split("->")[0];
					pos += original.length() - 1;
				}
			} else {
				sb.append(peptideSeq.charAt(pos - 1));
			}
		}
		return sb.toString();
	}

	private void load() throws IOException {
		if (loaded) {
			return;
		}
		super.clearSequences();
		super.clearDefs();
		if (proteinCacheFile.exists()) {
			final ReadLock readLock = lock.readLock();
			try {
				readLock.lock();
				final InputStreamReader isr = new InputStreamReader(new FileInputStream(proteinCacheFile), CHARSET);
				final BufferedReader br = new BufferedReader(isr);
				String line = null;
				while ((line = br.readLine()) != null) {
					final String[] split = line.split("\t");
					if (split.length == 2) {
						super.addProtein(split[1]);
					} else if (split.length > 2) {
						super.addProtein(split[1], split[2]);
					}
				}
				br.close();
			} finally {
				readLock.unlock();
			}
		}
		loaded = true;
	}

	@Override
	protected boolean isPopulated() {
		// load first
		try {
			load();
		} catch (final IOException e) {
			e.printStackTrace();
			log.warn("Error loading protein cache from protein cache file: '" + proteinCacheFile.getAbsolutePath()
					+ "'");
		}
		return super.isPopulated();
	}

	@Override
	public int addProtein(String def) {

		try {
			load();
			final int ret = super.getIndexOfDef(def);
			if (ret >= 0) {
				return ret;
			}
			final int index = super.addProtein(def);
			addToBuffer(index);
			return index;
		} catch (final IOException e) {
			e.printStackTrace();
		}
		return -1;
	}

	@Override
	public int addProtein(String def, String protein) {
		try {
			load();
			final int ret = super.getIndexOfDef(def);
			if (ret >= 0) {
				return ret;
			}

			final int index = super.addProtein(def, protein);
			addToBuffer(index);
			return index;
		} catch (final FileNotFoundException e) {
		} catch (final IOException e) {
			e.printStackTrace();
		}
		return -1;
	}

	private void addToBuffer(int index) throws IOException {
		indexesToWrite.add(index);
		// check if we should write
		if (indexesToWrite.size() >= bufferSize) {
			writeBuffer();
		}
	}

	public void writeBuffer() throws IOException {

		load();
		if (!indexesToWrite.isEmpty()) {
			log.debug("Writting protein cache to file...");
			final WriteLock writeLock = lock.writeLock();
			try {
				writeLock.lock();
				final OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(proteinCacheFile, true),
						CHARSET);
				for (final Integer index : indexesToWrite) {
					out.write(index + "\t" + super.getDef(index) + "\t" + super.getSequence(index) + "\n");
				}
				out.close();
				indexesToWrite.clear();

			} finally {
				writeLock.unlock();
			}

		}

	}

}
