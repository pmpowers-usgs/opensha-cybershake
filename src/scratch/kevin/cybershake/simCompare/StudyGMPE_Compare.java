package scratch.kevin.cybershake.simCompare;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;

import org.opensha.commons.data.Site;
import org.opensha.commons.data.region.CaliforniaRegions;
import org.opensha.commons.geo.Location;
import org.opensha.commons.util.ExceptionUtils;
import org.opensha.sha.cybershake.CyberShakeSiteBuilder;
import org.opensha.sha.cybershake.CyberShakeSiteBuilder.Vs30_Source;
import org.opensha.sha.cybershake.constants.CyberShakeStudy;
import org.opensha.sha.cybershake.db.CachedPeakAmplitudesFromDB;
import org.opensha.sha.cybershake.db.CybershakeRun;
import org.opensha.sha.cybershake.db.DBAccess;
import org.opensha.sha.cybershake.db.ERF2DB;
import org.opensha.sha.earthquake.AbstractERF;
import org.opensha.sha.earthquake.EqkRupture;
import org.opensha.sha.earthquake.ProbEqkRupture;
import org.opensha.sha.faultSurface.FaultTrace;
import org.opensha.sha.faultSurface.RuptureSurface;
import org.opensha.sha.imr.AttenRelRef;
import org.opensha.sha.imr.ScalarIMR;
import org.opensha.sha.imr.param.IntensityMeasureParams.SA_Param;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Table;
import com.google.common.primitives.Doubles;

import scratch.kevin.simCompare.MultiRupGMPE_ComparePageGen;
import scratch.kevin.simCompare.RuptureComparison;

public class StudyGMPE_Compare extends MultiRupGMPE_ComparePageGen<CSRupture> {
	
	private CachedPeakAmplitudesFromDB amps2db;
	private ERF2DB erf2db;
	private AbstractERF erf;
	private CyberShakeStudy study;
	
	private List<CybershakeRun> csRuns;
	private List<Site> sites;
	
	private StudyRotDProvider prov;
	
	private List<Site> highlightSites;
	
	private static double MAX_DIST = 200d;
	private static boolean DIST_JB = true;
	
	private Vs30_Source vs30Source;
	
	public StudyGMPE_Compare(CyberShakeStudy study, AbstractERF erf, DBAccess db, File ampsCacheDir, double[] periods,
			double minMag, HashSet<String> limitSiteNames, HashSet<String> highlightSiteNames, boolean doRotD,
			Vs30_Source vs30Source) throws IOException, SQLException {
		this.study = study;
		this.erf = erf;
		this.vs30Source = vs30Source;
		this.amps2db = new CachedPeakAmplitudesFromDB(db, ampsCacheDir, erf);
		this.erf2db = new ERF2DB(db);
		
		csRuns = study.runFetcher().fetch();
		System.out.println("Loaded "+csRuns.size()+" runs for "+study.getName()+" (dataset "+study.getDatasetIDs()+")");
		
		sites = CyberShakeSiteBuilder.buildSites(study, vs30Source, csRuns);
		
		if (limitSiteNames != null && !limitSiteNames.isEmpty()) {
			for (int i=sites.size(); --i>=0;) {
				if (!limitSiteNames.contains(sites.get(i).getName())) {
					sites.remove(i);
					csRuns.remove(i);
				}
			}
		}

		highlightSites = new ArrayList<>();
		if (highlightSiteNames != null) {
			for (Site site : sites)
				if (highlightSiteNames.contains(site.getName()))
					highlightSites.add(site);
		}
		
		prov = new StudyRotDProvider(study, amps2db, periods, study.getName());
		
		System.out.println("Done with setup");
		
		init(prov, sites, DIST_JB, MAX_DIST, minMag, 8.5);
	}
	
	static class CSRuptureComparison extends RuptureComparison.Cached<CSRupture> {
		
		private HashSet<Site> applicableSites;
		private CSRupture rupture;

		public CSRuptureComparison(CSRupture rupture) {
			super(rupture);
			
			applicableSites = new HashSet<>();
			this.rupture = rupture;
		}
		
		public void addApplicableSite(Site site) {
			applicableSites.add(site);
		}
		
		public boolean isSiteApplicable(Site site) {
			return applicableSites.contains(site);
		}

		@Override
		public Collection<Site> getApplicableSites() {
			return applicableSites;
		}

		@Override
		public EqkRupture getGMPERupture() {
			return getRupture().getRup();
		}

		@Override
		public double getMagnitude() {
			return getGMPERupture().getMag();
		}

		@Override
		public double getAnnualRate() {
			return getRupture().getRate();
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((rupture == null) ? 0 : rupture.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			CSRuptureComparison other = (CSRuptureComparison) obj;
			if (rupture == null) {
				if (other.rupture != null)
					return false;
			} else if (!rupture.equals(other.rupture))
				return false;
			return true;
		}

		@Override
		public double getRuptureTimeYears() {
			return rupture.getRuptureTimeYears();
		}
		
	}
	
	private class GMPECalcRunnable implements Runnable {
		
		private AttenRelRef gmpeRef;
		private Site site;
		private CSRuptureComparison comp;
		private double[] periods;

		public GMPECalcRunnable(AttenRelRef gmpeRef, Site site, CSRuptureComparison comp, double[] periods) {
			this.gmpeRef = gmpeRef;
			this.site = site;
			this.comp = comp;
			this.periods = periods;
		}

		@Override
		public void run() {
			ScalarIMR gmpe = checkOutGMPE(gmpeRef);
			
			gmpe.setAll(comp.getGMPERupture(), site, gmpe.getIntensityMeasure());
			
			RuptureSurface surf = comp.getGMPERupture().getRuptureSurface();
			comp.setDistances(site, surf.getDistanceRup(site.getLocation()), surf.getDistanceJB(site.getLocation()));
			
			for (double period : periods) {
				SA_Param.setPeriodInSA_Param(gmpe.getIntensityMeasure(), period);
				comp.addResult(site, period, gmpe.getMean(), gmpe.getStdDev());
			}
			
			checkInGMPE(gmpeRef, gmpe);
		}
		
	}
	
	public List<CSRuptureComparison> loadCalcComps(AttenRelRef gmpeRef, double[] periods) throws SQLException {
		CSRuptureComparison[][] comps = new CSRuptureComparison[erf.getNumSources()][];
		
		List<CSRuptureComparison> ret = new ArrayList<>();
		List<Future<?>> futures = new ArrayList<>();
		
		for (int r=0; r<csRuns.size(); r++) {
			Site site = sites.get(r);
			for (CSRupture siteRup : prov.getRupturesForSite(site)) {
				if (siteRup.getRate() == 0)
					continue;
				int sourceID = siteRup.getSourceID();
				int rupID = siteRup.getRupID();
				if (comps[sourceID] == null)
					comps[sourceID] = new CSRuptureComparison[erf.getNumRuptures(sourceID)];
				if (comps[sourceID][rupID] == null) {
					comps[sourceID][rupID] = new CSRuptureComparison(siteRup);
					ret.add(comps[sourceID][rupID]);
				}
				comps[sourceID][rupID].addApplicableSite(site);
				futures.add(exec.submit(new GMPECalcRunnable(gmpeRef, site, comps[sourceID][rupID], periods)));
			}
		}
		
		System.out.println("Waiting on "+futures.size()+" GMPE futures");
		for (Future<?> future : futures) {
			try {
				future.get();
			} catch (Exception e) {
				ExceptionUtils.throwAsRuntimeException(e);
			}
		}
		System.out.println("DONE with GMPE calc");
		
		return ret;
	}
	
	public void generateGMPE_page(File outputDir, AttenRelRef gmpeRef, double[] periods, List<CSRuptureComparison> comps)
			throws IOException {
		LinkedList<String> lines = new LinkedList<>();
		
		String distDescription = getDistDescription();
		
		// header
		lines.add("# "+study.getName()+"/"+gmpeRef.getShortName()+" GMPE Comparisons");
		lines.add("");
		lines.add("**GMPE: "+gmpeRef.getName()+"**");
		lines.add("");
		lines.add("**Vs30 Source: "+vs30Source+"**");
		lines.add("");
		lines.add("**Study Details**");
		lines.add("");
		lines.addAll(study.getMarkdownMetadataTable());
		lines.add("");
		lines.add("Ruptures are binned by their moment magnitude (**Mw**) and the "+distDescription);
		
		super.generateGMPE_Page(outputDir, lines, gmpeRef, periods, comps, highlightSites);
	}
	
	@Override
	protected double calcRupAzimuthDiff(CSRupture event, int rvIndex, Site site) {
		return calcRupAzimuthDiff(event, event.getHypocenter(rvIndex, erf2db), site.getLocation());
	}
	
	private static double calcRupAzimuthDiff(CSRupture event, Location hypo, Location siteLoc) {
		ProbEqkRupture rup = event.getRup();
		RuptureSurface surf = rup.getRuptureSurface();
		FaultTrace upperEdge = surf.getEvenlyDiscritizedUpperEdge();
		Location rupStart = upperEdge.first();
		Location rupEnd = upperEdge.last();
		Location centroid;
		if (upperEdge.size() % 2 == 0) {
			Location l1 = upperEdge.get(upperEdge.size()/2 - 1);
			Location l2 = upperEdge.get(upperEdge.size()/2);
			centroid = new Location(0.5*(l1.getLatitude() + l2.getLatitude()), 0.5*(l1.getLongitude() + l2.getLongitude()));
		} else {
			centroid = upperEdge.get(upperEdge.size()/2);
		}
		return calcRupAzimuthDiff(rupStart, rupEnd, centroid, hypo, siteLoc);
	}
	
	public void generateRotDRatioPage(File outputDir, double[] aggregatedPeriods, double[] scatterPeriods, AttenRelRef gmpeRef,
			List<CSRuptureComparison> gmpeComps) throws IOException, SQLException {
		Preconditions.checkState(prov.hasRotD100());
		File resourcesDir = new File(outputDir, "resources");
		Preconditions.checkState(resourcesDir.exists() || resourcesDir.mkdir());
		LinkedList<String> lines = new LinkedList<>();
		
		String distDescription = getDistDescription();
		
		// header
		lines.add("# "+study.getName()+"/"+gmpeRef.getShortName()+" RotD100/RotD50 Ratios");
		lines.add("");
		lines.add("**Study Details**");
		lines.add("");
		lines.addAll(study.getMarkdownMetadataTable());
		lines.add("");
		lines.add("Distance dependent plots use the "+distDescription+" distance metric");
		lines.add("");
		
		if (gmpeComps == null)
			gmpeComps = loadCalcComps(gmpeRef, aggregatedPeriods);
		
		super.generateRotDRatioPage(outputDir, lines, aggregatedPeriods, scatterPeriods, gmpeRef, gmpeComps);
	}
	
	private static double[] union(double[] array1, double[] array2) {
		HashSet<Double> set = new HashSet<>();
		for (double val : array1)
			set.add(val);
		for (double val : array2)
			set.add(val);
		double[] ret = Doubles.toArray(set);
		Arrays.sort(ret);
		return ret;
	}
	
	public static void main(String[] args) throws IOException, SQLException {
		File outputDir = new File("/home/kevin/git/cybershake-analysis/");
		File ampsCacheDir = new File("/data/kevin/cybershake/amps_cache/");
		
		List<CyberShakeStudy> studies = new ArrayList<>();
		List<Vs30_Source> vs30s = new ArrayList<>();
		
//		studies.add(CyberShakeStudy.STUDY_18_9_RSQSIM_2740);
//		vs30s.add(Vs30_Source.Simulation);
		
//		studies.add(CyberShakeStudy.STUDY_18_4_RSQSIM_2585);
//		vs30s.add(Vs30_Source.Simulation);
		
//		studies.add(CyberShakeStudy.STUDY_18_4_RSQSIM_PROTOTYPE_2457);
//		vs30s.add(Vs30_Source.Simulation);
//		studies.add(CyberShakeStudy.STUDY_18_4_RSQSIM_PROTOTYPE_2457);
//		vs30s.add(Vs30_Source.Simulation);
		
//		studies.add(CyberShakeStudy.STUDY_17_3_3D);
//		vs30s.add(Vs30_Source.Simulation);
//		studies.add(CyberShakeStudy.STUDY_17_3_3D);
//		vs30s.add(Vs30_Source.Wills2015);
		
//		studies.add(CyberShakeStudy.STUDY_17_3_1D);
//		vs30s.add(Vs30_Source.Simulation);
		
		studies.add(CyberShakeStudy.STUDY_15_4);
		vs30s.add(Vs30_Source.Simulation);
//		studies.add(CyberShakeStudy.STUDY_15_4);
//		vs30s.add(Vs30_Source.Wills2015);
		
		AttenRelRef primaryGMPE = AttenRelRef.ASK_2014; // this one will include highlight sites
//		AttenRelRef[] gmpeRefs = { AttenRelRef.NGAWest_2014_AVG_NOIDRISS, AttenRelRef.ASK_2014,
//				AttenRelRef.BSSA_2014, AttenRelRef.CB_2014, AttenRelRef.CY_2014 };
//		AttenRelRef[] gmpeRefs = { AttenRelRef.NGAWest_2014_AVG_NOIDRISS, AttenRelRef.ASK_2014 };
		AttenRelRef[] gmpeRefs = { AttenRelRef.ASK_2014 };
		
//		double[] periods = { 2, 3, 5 };
//		double[] rotDPeriods = { 2, 3, 5, 7.5, 10 };
		double[] periods = { 3, 5, 10 };
//		double[] periods = { 3 };
		double[] rotDPeriods = { 3, 5, 7.5, 10 };
		double minMag = 6;
		
		boolean doGMPE = true;
		boolean doRotD = false;
		
		boolean limitToHighlight = false;
		
		boolean replotScatters = false;
		boolean replotZScores = false;
		boolean replotCurves = false;
		boolean replotResiduals = false;
		
		for (int s=0; s<studies.size(); s++) {
			System.gc();
			
			CyberShakeStudy study = studies.get(s);
			Vs30_Source vs30Source = vs30s.get(s);
			
			File studyDir = new File(outputDir, study.getDirName());
			Preconditions.checkState(studyDir.exists() || studyDir.mkdir());
			
			HashSet<String> highlightSiteNames = new HashSet<>();
			if (study.getRegion() instanceof CaliforniaRegions.CYBERSHAKE_CCA_MAP_REGION) {
				highlightSiteNames.add("SBR");
				highlightSiteNames.add("VENT");
				highlightSiteNames.add("BAK");
				highlightSiteNames.add("CAR");
				highlightSiteNames.add("SLO");
				highlightSiteNames.add("LEM");
			} else {
				highlightSiteNames.add("USC");
				highlightSiteNames.add("SBSM");
				highlightSiteNames.add("PAS");
//				highlightSiteNames.add("COO");
				highlightSiteNames.add("WNGC");
				highlightSiteNames.add("LAPD");
				highlightSiteNames.add("STNI");
//				highlightSiteNames.add("FIL");
			}
			HashSet<String> limitSiteNames;
			if (limitToHighlight)
				limitSiteNames = highlightSiteNames;
			else
				limitSiteNames = null;
			
			double[] calcPeriods = null;
			Preconditions.checkState(doGMPE || doRotD);
			if (doGMPE)
				calcPeriods = periods;
			if (doRotD) {
				if (calcPeriods == null)
					calcPeriods = rotDPeriods;
				else
					calcPeriods = union(rotDPeriods, calcPeriods);
			}
			
			if (limitSiteNames == null)
				CachedPeakAmplitudesFromDB.MAX_CACHE_SIZE = 50;
			else
				CachedPeakAmplitudesFromDB.MAX_CACHE_SIZE = limitSiteNames.size()*periods.length*2+10;
			DBAccess db = study.getDB();
			
			AbstractERF erf = study.getERF();
			erf.getTimeSpan().setDuration(1d);
			erf.updateForecast();
			
			System.out.println("Calc periods: "+Joiner.on(",").join(Doubles.asList(calcPeriods)));
			
			try {
				StudyGMPE_Compare comp = new StudyGMPE_Compare(study, erf, db, ampsCacheDir, calcPeriods,
						minMag, limitSiteNames, highlightSiteNames, doRotD, vs30Source);
				comp.setReplotCurves(replotCurves);
				comp.setReplotResiduals(replotResiduals);
				comp.setReplotScatters(replotScatters);
				comp.setReplotZScores(replotZScores);
				
				HashSet<CSRupture> allRups = new HashSet<>();
				for (Site site : comp.sites)
					allRups.addAll(comp.prov.getRupturesForSite(site));
				Table<String, CSRupture, Double> sourceContribFracts =
						StudySiteHazardCurvePageGen.getSourceContribFracts(
								study.getERF(), allRups, study.getRSQSimCatalog(), true);
				comp.setSourceRupContributionFractions(sourceContribFracts, 10);
				
				List<Site> highlightSites = comp.highlightSites;
				
				for (AttenRelRef gmpeRef : gmpeRefs) {
					List<CSRuptureComparison> comps;
					if (gmpeRef == primaryGMPE) {
						comp.highlightSites = highlightSites;
						comps = comp.loadCalcComps(gmpeRef, calcPeriods);
					} else {
						if (!doGMPE)
							continue;
						// don't include RotD periods
						comps = comp.loadCalcComps(gmpeRef, periods);
						comp.highlightSites = new ArrayList<>();
					}
					
					if (doGMPE) {
						File catalogGMPEDir = new File(studyDir, "gmpe_comparisons_"+gmpeRef.getShortName()+"_Vs30"+vs30Source.name());
						Preconditions.checkState(catalogGMPEDir.exists() || catalogGMPEDir.mkdir());
						comp.generateGMPE_page(catalogGMPEDir, gmpeRef, periods, comps);
					}
					
					if (doRotD && gmpeRef == primaryGMPE) {
						File catalogRotDDir = new File(studyDir, "rotd_ratio_comparisons");
						Preconditions.checkState(catalogRotDDir.exists() || catalogRotDDir.mkdir());
						comp.generateRotDRatioPage(catalogRotDDir, rotDPeriods, periods, gmpeRef, comps);
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			System.out.println("Done with "+study+" at "+System.currentTimeMillis()+", writing markdown summary and updating index");
			
			study.writeMarkdownSummary(studyDir);
			CyberShakeStudy.writeStudiesIndex(outputDir);
		}
		System.out.println("Done with all at "+System.currentTimeMillis());
		
		for (CyberShakeStudy study : studies)
			study.getDB().destroy();
		
		System.exit(0);
	}

}
