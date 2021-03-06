package com.derpthemeus.runeCoach.databasePopulator.populatorThreads;

import com.derpthemeus.runeCoach.DDragonManager;
import com.derpthemeus.runeCoach.databasePopulator.PopulatorThread;
import com.derpthemeus.runeCoach.databasePopulator.threadSupervisors.StatAggregatorSupervisor;
import com.derpthemeus.runeCoach.hibernate.AggregatedStatsEntity;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.Calendar;

public class StatAggregatorThread extends PopulatorThread {

	private AggregatedStatsEntity stat;
	// The LoL patch (e.g. "7.24") that this thread is aggregating stats for
	private String patch;

	@Override
	public void runOperation() {
		// Use the latest patch if one hasn't been specified
		if (patch == null) {
			getLogger().info("Defaulting to most recent patch");
			try {
				patch = DDragonManager.convertToShortVersion(DDragonManager.getLatestVersion());
			} catch (IOException ex) {
				getLogger().error("Error getting DDragon version", ex);
				return;
			}
		}

		stat = getSupervisor().getStatToAggregate(patch);
		// Sleep for 10 seconds if there is no work to be done
		if (stat == null) {
			try {
				Thread.sleep(10000);
			} catch (InterruptedException ex) {
				getLogger().throwing(ex);
			}
			return;
		}

		Transaction tx = null;
		try (Session session = getSupervisor().getSessionFactory().openSession()) {
			tx = session.beginTransaction();
			// TODO lock `stat`?

			Query query;
			if (stat.getChampionId() > 0) {
				// Aggregate stats for a single champion
				query = session.createQuery(
						"SELECT COUNT(*),SUM(perk.var1),SUM(perk.var2),SUM(perk.var3),SUM(CASE WHEN participant.winner=TRUE THEN 1 ELSE 0 END),MAX(perk.playerId)" +
								" FROM ParticipantPerkEntity AS perk INNER JOIN ParticipantEntity AS participant ON participant.playerId=perk.playerId INNER JOIN MatchEntity AS game ON game.matchId=participant.matchId" +
								" WHERE participant.championId=:champId AND perk.perkId=:perkId AND game.patch=:patch AND participant.playerId>:lastPlayerId"
				).setParameter("perkId", stat.getPerkId()).setParameter("champId", stat.getChampionId()).setParameter("patch", stat.getPatch()).setParameter("lastPlayerId", stat.getLastPlayerId());
			} else {
				// Aggregate stats for a tag
				query = session.createQuery("SELECT CAST(SUM(totalMatches) AS java.lang.Long),SUM(var1Total),SUM(var2Total),SUM(var3Total),SUM(totalWins),CAST(0 AS java.lang.Long) FROM AggregatedStatsEntity" +
						" WHERE championId IN (SELECT championId FROM TagChampionEntity WHERE -tagId=:championId) AND perkId=:perkId AND patch=:patch"
				).setParameter("perkId", stat.getPerkId()).setParameter("championId", stat.getChampionId()).setParameter("patch", stat.getPatch());
			}

			Object[] result = (Object[]) query.getSingleResult();
			Long count = (Long) result[0];
			// The values for `SUM` and `MAX` columns will be `null` if `count` is 0
			if (count != null && count > 0) {
				stat.setTotalMatches(stat.getTotalMatches() + count);
				stat.setVar1Total(stat.getVar1Total() + (long) result[1]);
				stat.setVar2Total(stat.getVar2Total() + (long) result[2]);
				stat.setVar3Total(stat.getVar3Total() + (long) result[3]);

				stat.setTotalWins(stat.getTotalWins() + (long) result[4]);
				stat.setLastPlayerId((long) result[5]);

			}
			stat.setLastUpdated(new Timestamp(Calendar.getInstance().getTimeInMillis()));

			session.update(stat);
			tx.commit();
		} catch (Exception ex) {
			if (tx != null) {
				tx.markRollbackOnly();
			}
			getLogger().error("Error aggregating stats for perk " + stat.getPerkId() + " on champion " + stat.getChampionId() + " during patch " + stat.getPatch(), ex);
		}
		stat = null;
	}

	@Override
	public StatAggregatorSupervisor getSupervisor() {
		return StatAggregatorSupervisor.getInstance();
	}

	public AggregatedStatsEntity getActiveStat() {
		return this.stat;
	}

	/**
	 * Sets the patch that this thread should aggregate stats for. If not called or the patch is set to `null`, the thread will aggregate stats for the latest patch (as of the time that it starts)
	 */
	public void setPatch(String patch) {
		this.patch = patch;
	}
}
