package com.ir.hbase.hive;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.PrefixFilter;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hive.ql.stats.StatsAggregator;

public class HBaseStatsAggregator implements StatsAggregator {

	  private HTable htable;
	  private final Log LOG = LogFactory.getLog(this.getClass().getName());

	  /**
	   * Does the necessary HBase initializations.
	   */
	  public boolean connect(Configuration hiveconf) {

	    try {
	      Configuration hbaseConf = HBaseConfiguration.create(hiveconf);
	      htable = new HTable(hbaseConf, HBaseStatsSetupConstants.PART_STAT_TABLE_NAME);

	      return true;
	    } catch (IOException e) {
	      LOG.error("Error during HBase connection. ", e);
	      return false;
	    }
	  }

	  /**
	   * Aggregates temporary stats from HBase;
	   */
	  public String aggregateStats(String rowID, String key) {

	    byte[] family, column;
	    if (!HBaseStatsUtils.isValidStatistic(key)) {
	      LOG.warn("Warning. Invalid statistic: " + key + ", supported stats: " +
	          HBaseStatsUtils.getSupportedStatistics());
	      return null;
	    }

	    family = HBaseStatsUtils.getFamilyName();
	    column = HBaseStatsUtils.getColumnName(key);

	    try {

	      long retValue = 0;
	      Scan scan = new Scan();
	      scan.addColumn(family, column);
	      // Filter the row by its ID
	      // The complete key is "tableName/PartSpecs/jobID/taskID"
	      // This is a prefix filter, the prefix is "tableName/PartSpecs/JobID", i.e. the taskID is
	      // ignored. In SQL, this is equivalent to
	      // "Select * FROM tableName where ID LIKE 'tableName/PartSpecs/JobID%';"
	      PrefixFilter filter = new PrefixFilter(Bytes.toBytes(rowID));
	      scan.setFilter(filter);
	      ResultScanner scanner = htable.getScanner(scan);

	      for (Result result : scanner) {
	        retValue += Long.parseLong(Bytes.toString(result.getValue(family, column)));
	      }
	      return Long.toString(retValue);
	    } catch (IOException e) {
	      LOG.error("Error during publishing aggregation. ", e);
	      return null;
	    }
	  }

	  public boolean closeConnection() {
	    return true;
	  }

	  public boolean cleanUp(String rowID) {
	    try {
	      Scan scan = new Scan();
	      // Filter the row by its ID
	      // The complete key is "tableName/PartSpecs/jobID/taskID"
	      // This is a prefix filter, the prefix is "JobID"
	      // In SQL, this is equivalent to "Select * FROM tableName where ID LIKE 'JobID%';"
	      PrefixFilter filter = new PrefixFilter(Bytes.toBytes(rowID));
	      scan.setFilter(filter);
	      ResultScanner scanner = htable.getScanner(scan);
	      ArrayList<Delete> toDelete = new ArrayList<Delete>();
	      for (Result result : scanner) {
	        Delete delete = new Delete(result.getRow());
	        toDelete.add(delete);
	      }
	      htable.delete(toDelete);
	      return true;
	    } catch (IOException e) {
	      LOG.error("Error during publishing aggregation. ", e);
	      return false;
	    }
	  }
	}