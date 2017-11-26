package com.wavedroid.musicbrainz.dao;

import com.google.common.collect.Maps;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author Dmitriy Khvatov (<i>dimax4@gmail.com</i>)
 * @version $Id$
 */
public class MusicbrainzDao {

    private static final Logger LOGGER = LoggerFactory.getLogger(MusicbrainzDao.class);
    private static final int PAGE_SIZE = 100;

    private final PGSimpleDataSource dataSource;

    private static final String ENVVAR_DB_URL = "MBZ_DB_URL";
    private static final String ENVVAR_DB_USER = "MBZ_DB_USER";
    private static final String ENVVAR_DB_PASSWORD = "MBZ_DB_PASSWORD";

    private static final MusicbrainzDao INSTANCE = new MusicbrainzDao();

    private static final String RELEASES_BY_ARTISTS = "SELECT\n" +
            "  rg_year  AS year,\n" +
            "  rg_month AS month,\n" +
            "  artist,\n" +
            "  release_name,\n" +
            "  total_tracks,\n" +
            "  artist_id,\n" +
            "  release_group_id,\n" +
            "  release_mbid,\n" +
            "  release_group_mbid,\n" +
            "  CASE WHEN " +
            "       (tbl2.type = 1\n" +
            "           AND NOT exists(SELECT 1\n" +
            "                          FROM release_group_secondary_type_join j\n" +
            "                          WHERE j.release_group = tbl2.release_group_id))\n" +
            "  THEN 1\n" +
            "  ELSE 0\n" +
            "  END AS is_studio_album\n" +
            "FROM (\n" +
            "       SELECT DISTINCT ON (release_group_id)\n" +
            "         sum(track_count)\n" +
            "         OVER (PARTITION BY release_id) total_tracks,\n" +
            "         *\n" +
            "       FROM (\n" +
            "              SELECT\n" +
            "                r.type,\n" +
            "                re.country,\n" +
            "                medium.track_count         AS track_count,\n" +
            "                medium.id                  AS medium_id,\n" +
            "                r.id                       AS release_group_id,\n" +
            "                rel.id                     AS release_id,\n" +
            "                r.gid                      AS release_group_mbid,\n" +
            "                rel.gid                    AS release_mbid,\n" +
            "                a.id                       AS artist_id,\n" +
            "                a.name                     AS artist,\n" +
            "                r.name                     AS release_name,\n" +
            "                re.date_year               AS year,\n" +
            "                re.date_month              AS month,\n" +
            "                m.first_release_date_year  AS rg_year,\n" +
            "                m.first_release_date_month AS rg_month,\n" +
            "                a.rank                     AS rank\n" +
            "              FROM\n" +
            "                (SELECT\n" +
            "                   name,\n" +
            "                   id,\n" +
            "                   ts_rank_cd(ts_name, plainto_tsquery('mb_simple', ?), 2) rank\n" +
            "                 FROM artist\n" +
            "                 WHERE ts_name @@ plainto_tsquery('mb_simple', ?)\n" +
            "                ) AS a\n" +
            "                INNER JOIN artist_credit_name c ON a.id = c.artist\n" +
            "                INNER JOIN release_group r ON c.artist_credit = r.artist_credit\n" +
            "                INNER JOIN release_group_meta m ON m.id = r.id\n" +
            "                INNER JOIN release rel ON rel.release_group = r.id\n" +
            "                INNER JOIN release_event re ON re.release = rel.id\n" +
            "                INNER JOIN medium ON medium.release = rel.id\n" +
            "                                     AND ((r.type = 1\n" +
            "                                          AND NOT exists(SELECT 1\n" +
            "                                                    FROM release_group_secondary_type_join j\n" +
            "                                                    WHERE j.release_group = r.id))\n" +
            "                                           OR ?)\n" +
            "            )\n" +
            "         AS tbl ORDER BY release_group_id, year ASC) AS tbl2\n" +
            "ORDER BY rank DESC, year, month\n";

    private static final String RELEASE_BY_ID = "SELECT\n" +
            "  rg_year  AS year,\n" +
            "  rg_month AS month,\n" +
            "  artist,\n" +
            "  release_name,\n" +
            "  total_tracks,\n" +
            "  artist_id,\n" +
            "  release_group_id,\n" +
            "  release_mbid,\n" +
            "  release_group_mbid\n" +
            "FROM (\n" +
            "       SELECT DISTINCT ON (release_group_id)\n" +
            "         sum(track_count)\n" +
            "         OVER (PARTITION BY release_id) total_tracks,\n" +
            "         *\n" +
            "       FROM (\n" +
            "              SELECT\n" +
            "                r.type,\n" +
            "                re.country,\n" +
            "                medium.track_count         AS track_count,\n" +
            "                medium.id                  AS medium_id,\n" +
            "                r.id                       AS release_group_id,\n" +
            "                rel.id                     AS release_id,\n" +
            "                r.gid                      AS release_group_mbid,\n" +
            "                rel.gid                    AS release_mbid,\n" +
            "                a.id                       AS artist_id,\n" +
            "                a.name                     AS artist,\n" +
            "                r.name                     AS release_name,\n" +
            "                re.date_year               AS year,\n" +
            "                re.date_month              AS month,\n" +
            "                m.first_release_date_year  AS rg_year,\n" +
            "                m.first_release_date_month AS rg_month\n" +
            "              FROM artist a\n" +
            "                INNER JOIN artist_credit_name c ON a.id = c.artist\n" +
            "                INNER JOIN release_group r ON c.artist_credit = r.artist_credit\n" +
            "                INNER JOIN release_group_meta m ON m.id = r.id\n" +
            "                INNER JOIN release rel ON rel.release_group = r.id\n" +
            "                INNER JOIN release_event re ON re.release = rel.id\n" +
            "                INNER JOIN medium ON medium.release = rel.id\n" +
            "              WHERE r.id = ?\n" +
            "            )\n" +
            "         AS tbl) AS tbl2\n" +
            "ORDER BY artist, rg_year, rg_month\n";

    private static final String RELEASE_BY_MBID = "SELECT\n" +
            "  rg_year  AS year,\n" +
            "  rg_month AS month,\n" +
            "  artist,\n" +
            "  release_id,\n" +
            "  release_name,\n" +
            "  total_tracks,\n" +
            "  artist_id,\n" +
            "  release_group_id,\n" +
            "  release_mbid,\n" +
            "  release_group_mbid\n" +
            "FROM (\n" +
            "            SELECT * FROM (\n" +
            "              SELECT\n" +
            "                r.type,\n" +
            "                re.country,\n" +
            "                sum(medium.track_count) OVER (PARTITION BY rel.id) as total_tracks,\n" +
            "                medium.id                  AS medium_id,\n" +
            "                r.id                       AS release_group_id,\n" +
            "                rel.id                     AS release_id,\n" +
            "                r.gid                      AS release_group_mbid,\n" +
            "                rel.gid                    AS release_mbid,\n" +
            "                a.id                       AS artist_id,\n" +
            "                a.name                     AS artist,\n" +
            "                r.name                     AS release_name,\n" +
            "                re.date_year               AS year,\n" +
            "                re.date_month              AS month,\n" +
            "                m.first_release_date_year  AS rg_year,\n" +
            "                m.first_release_date_month AS rg_month,\n" +
            "                row_number() OVER (PARTITION BY r.gid order by\n" +
            "                                       to_date(re.date_year || '-' ||\n" +
            "                                               COALESCE(re.date_month, 12) || '-' ||\n" +
            "                                               COALESCE(re.date_day,\n" +
            "                                                         date_part('day',\n" +
            "                                                                   (re.date_year || '-' ||\n" +
            "                                                                    COALESCE(re.date_month, 12) || '-01')::date \n" +
            "                                                                    + '1 month'::interval\n" +
            "                                                                    - '1 day'::interval)\n" +
            "                                                                   )\n" +
            "                                       , 'YYYY-MM-DD')\n" +
            "                 ) AS rownum\n" +
            "              FROM artist a\n" +
            "                INNER JOIN artist_credit_name c ON a.id = c.artist\n" +
            "                INNER JOIN release_group r ON c.artist_credit = r.artist_credit\n" +
            "                INNER JOIN release_group_meta m ON m.id = r.id\n" +
            "                INNER JOIN release rel ON rel.release_group = r.id\n" +
            "                INNER JOIN release_event re ON re.release = rel.id\n" +
            "                INNER JOIN medium ON medium.release = rel.id\n" +
            "              WHERE r.gid = CAST(? AS UUID)\n" +
            "              ) AS tbl WHERE tbl.rownum = 1\n" +
            "         ) AS tbl2\n" +
            "ORDER BY artist, rg_year, rg_month";

    private static final String RELEASE_BY_NAME = "SELECT\n" +
            "  rg_year  AS year,\n" +
            "  rg_month AS month,\n" +
            "  artist,\n" +
            "  release_name,\n" +
            "  total_tracks,\n" +
            "  artist_id,\n" +
            "  release_group_id,\n" +
            "  release_mbid,\n" +
            "  release_group_mbid,\n" +
            "  CASE WHEN " +
            "       (tbl2.type = 1\n" +
            "           AND NOT exists(SELECT 1\n" +
            "                          FROM release_group_secondary_type_join j\n" +
            "                          WHERE j.release_group = tbl2.release_group_id))\n" +
            "  THEN 1\n" +
            "  ELSE 0\n" +
            "  END AS is_studio_album\n" +
            "FROM (\n" +
            "       SELECT DISTINCT ON (release_group_id)\n" +
            "         sum(track_count)\n" +
            "         OVER (PARTITION BY release_id) total_tracks,\n" +
            "         *\n" +
            "       FROM (\n" +
            "              SELECT\n" +
            "                r.type,\n" +
            "                re.country,\n" +
            "                medium.track_count         AS track_count,\n" +
            "                medium.id                  AS medium_id,\n" +
            "                r.id                       AS release_group_id,\n" +
            "                rel.id                     AS release_id,\n" +
            "                r.gid                      AS release_group_mbid,\n" +
            "                rel.gid                    AS release_mbid,\n" +
            "                a.id                       AS artist_id,\n" +
            "                a.name                     AS artist,\n" +
            "                r.name                     AS release_name,\n" +
            "                re.date_year               AS year,\n" +
            "                re.date_month              AS month,\n" +
            "                m.first_release_date_year  AS rg_year,\n" +
            "                m.first_release_date_month AS rg_month,\n" +
            "                r.rank                     AS rank\n" +
            "              FROM\n" +
            "                (SELECT\n" +
            "                   name,\n" +
            "                   type,\n" +
            "                   id,\n" +
            "                   gid,\n" +
            "                   artist_credit,\n" +
            "                   ts_rank_cd(ts_name, plainto_tsquery('mb_simple', ?), 2) rank\n" +
            "                 FROM release_group\n" +
            "                 WHERE ts_name @@ plainto_tsquery('mb_simple', ?)\n" +
            "                 ORDER BY rank DESC\n" +
            "                ) AS r\n" +
            "                INNER JOIN artist_credit_name c ON r.artist_credit = c.artist_credit\n" +
            "                INNER JOIN artist a ON a.id = c.artist_credit\n" +
            "                INNER JOIN release_group_meta m ON m.id = r.id\n" +
            "                INNER JOIN release rel ON rel.release_group = r.id\n" +
            "                INNER JOIN release_event re ON re.release = rel.id\n" +
            "                INNER JOIN medium ON medium.release = rel.id\n" +
            "                                     AND ((r.type = 1\n" +
            "                                           AND NOT exists(SELECT 1\n" +
            "                                                    FROM release_group_secondary_type_join j\n" +
            "                                                    WHERE j.release_group = r.id))\n" +
            "                                           OR ?)\n" +
            "            )\n" +
            "         AS tbl ORDER BY release_group_id, year ASC) AS tbl2\n" +
            "ORDER BY rank DESC, year, month\n";

    private static final String RELEASE_BY_ARTIST = "SELECT\n" +
            "  rg_year  AS year,\n" +
            "  rg_month AS month,\n" +
            "  artist,\n" +
            "  release_name,\n" +
            "  total_tracks,\n" +
            "  artist_id,\n" +
            "  release_group_id,\n" +
            "  release_mbid,\n" +
            "  release_group_mbid,\n" +
            "  CASE WHEN " +
            "       (tbl2.type = 1\n" +
            "           AND NOT exists(SELECT 1\n" +
            "                          FROM release_group_secondary_type_join j\n" +
            "                          WHERE j.release_group = tbl2.release_group_id))\n" +
            "  THEN 1\n" +
            "  ELSE 0\n" +
            "  END AS is_studio_album\n" +
            "FROM (\n" +
            "       SELECT DISTINCT ON (release_group_id)\n" +
            "         sum(track_count)\n" +
            "         OVER (PARTITION BY release_id) total_tracks,\n" +
            "         *\n" +
            "       FROM (\n" +
            "              SELECT\n" +
            "                r.type,\n" +
            "                re.country,\n" +
            "                medium.track_count         AS track_count,\n" +
            "                medium.id                  AS medium_id,\n" +
            "                r.id                       AS release_group_id,\n" +
            "                rel.id                     AS release_id,\n" +
            "                r.gid                      AS release_group_mbid,\n" +
            "                rel.gid                    AS release_mbid,\n" +
            "                a.id                       AS artist_id,\n" +
            "                a.name                     AS artist,\n" +
            "                r.name                     AS release_name,\n" +
            "                re.date_year               AS year,\n" +
            "                re.date_month              AS month,\n" +
            "                m.first_release_date_year  AS rg_year,\n" +
            "                m.first_release_date_month AS rg_month\n" +
            "              FROM artist a\n" +
            "                INNER JOIN artist_credit_name c ON a.id = c.artist\n" +
            "                INNER JOIN release_group r ON c.artist_credit = r.artist_credit\n" +
            "                INNER JOIN release_group_meta m ON m.id = r.id\n" +
            "                INNER JOIN release rel ON rel.release_group = r.id\n" +
            "                INNER JOIN release_event re ON re.release = rel.id\n" +
            "                INNER JOIN medium ON medium.release = rel.id\n" +
            "              WHERE a.id = ?\n" +
            "                                     AND ((r.type = 1\n" +
            "                                           AND NOT exists(SELECT 1\n" +
            "                                                    FROM release_group_secondary_type_join j\n" +
            "                                                    WHERE j.release_group = r.id))\n" +
            "                                           OR ?)\n" +

            "            )\n" +
            "         AS tbl ORDER BY release_group_id, year ASC) AS tbl2\n" +
            "ORDER BY year, month ";

    private static final String TRACKLIST_BY_RELEASE_ID = "SELECT\n" +
            "  t.id             AS track_id,\n" +
            "  t.name           AS title,\n" +
            "  t.length         AS length,\n" +
            "  t.position       AS position,\n" +
            "  tbl2.disc_number AS disc_number\n" +
            "FROM (\n" +
            "       SELECT\n" +
            "         sum(track_count)\n" +
            "         OVER (PARTITION BY release_id) total_tracks,\n" +
            "         *\n" +
            "       FROM (\n" +
            "              SELECT\n" +
            "                m.track_count AS track_count,\n" +
            "                m.id          AS medium_id,\n" +
            "                m.position    AS disc_number,\n" +
            "                r.id          AS release_group_id,\n" +
            "                rel.id        AS release_id\n" +
            "              FROM artist a\n" +
            "                INNER JOIN artist_credit_name c ON a.id = c.artist\n" +
            "                INNER JOIN release_group r ON c.artist_credit = r.artist_credit\n" +
            "                INNER JOIN release rel ON rel.release_group = r.id\n" +
            "                INNER JOIN medium m ON m.release = rel.id\n" +
            "              WHERE rel.id = ?) AS tbl) AS tbl2\n" +
            "  INNER JOIN track t ON t.medium = medium_id\n" +
            "ORDER BY disc_number, t.position \n";

    private static final String TRACKLIST_BY_RELEASE_MBID = "SELECT\n" +
      "  t.id                     AS track_id,\n" +
      "  t.name                   AS title,\n" +
      "  t.length                 AS length,\n" +
      "  t.position               AS position,\n" +
      "  m.position               AS disc_number,\n" +
      "  m.track_count            AS track_count,\n" +
      "  tbl.release_id           AS release_id,\n" +
      "  tbl.release_id           AS release_group_id,\n" +
      "  SUM(m.track_count)\n" +
      "  OVER (PARTITION BY m.id) AS total_tracks\n" +
      "FROM (SELECT MIN(rel.id) AS release_id\n" +
      "      FROM release_group r\n" +
      "        INNER JOIN release rel ON rel.release_group = r.id\n" +
      "      WHERE r.gid = CAST(? AS UUID)\n" +
      "     ) AS tbl\n" +
      "  INNER JOIN medium m ON m.release = tbl.release_id\n" +
      "  INNER JOIN track t ON t.medium = m.id\n" +
      "ORDER BY disc_number, t.position\n";

    private static final String TAGS_BY_RELEASE_GROUPS = "" +
            "SELECT\n" +
            "  release_group_id,\n" +
            "  tag\n" +
            "FROM (\n" +
            "       SELECT\n" +
            "         release_group          AS release_group_id,\n" +
            "         name                   AS tag,\n" +
            "         row_number()\n" +
            "         OVER (PARTITION BY release_group\n" +
            "           ORDER BY count DESC) AS row_number\n" +
            "       FROM release_group_tag rt\n" +
            "         LEFT OUTER JOIN tag tag ON rt.tag = tag.id\n" +
            "       WHERE release_group = ANY (?)\n" +
            "     ) AS t\n" +
            "WHERE t.row_number <= ?\n";

    private MusicbrainzDao() {
        try {
            dataSource = new PGSimpleDataSource();

            Map<String, String> env = System.getenv();

            dataSource.setUrl(env.getOrDefault(ENVVAR_DB_URL, "jdbc:postgresql://localhost:5433/musicbrainz_db"));
            dataSource.setUser(env.getOrDefault(ENVVAR_DB_USER, "musicbrainz"));
            dataSource.setPassword(env.getOrDefault(ENVVAR_DB_PASSWORD, "musicbrainz"));
            dataSource.getConnection();
        } catch (SQLException e) {
            throw new RuntimeException("FATAL: Unable to connect to musicbrainz database.", e);
        }
    }

    public static List<Map<String, Object>> getReleasesByArtists(String artist, boolean all, int page) {
        return getEntitiesFromResultSet(getResultSet(RELEASES_BY_ARTISTS, page, artist, artist, all).orElse(null));
    }

    public static Map<String, Object> getReleaseById(long id) {
        try {
            Optional<ResultSet> rs = getResultSet(RELEASE_BY_ID, 0, id);
            if (rs.isPresent()) {
                if (rs.get().next()) {
                    return getEntityFromResultSet(rs.get());
                } else {
                    return Maps.newHashMap();
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Error querying release by id", e);
        }
        return Maps.newHashMap();
    }

    public static Map<String, Object> getReleaseByMbid(String mbid) {
        try {
            Optional<ResultSet> rs = getResultSet(RELEASE_BY_MBID, 0, mbid);
            if (rs.isPresent()) {
                if (rs.get().next()) {
                    return getEntityFromResultSet(rs.get());
                } else {
                    return Maps.newHashMap();
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Error querying release by mbid", e);
        }
        return Maps.newHashMap();
    }

    public static List<Map<String, Object>> getReleasesByName(String name, boolean all, int page) {
        return getEntitiesFromResultSet(getResultSet(RELEASE_BY_NAME, page, name, name, all).orElse(null));
    }

    public static List<Map<String, Object>> getReleasesByArtist(long artistId, boolean all, int page) {
        return getEntitiesFromResultSet(getResultSet(RELEASE_BY_ARTIST, page, artistId, all).orElse(null));
    }

    public static List<Map<String, Object>> getTracklist(long releaseId, int page) {
        return getEntitiesFromResultSet(getResultSet(TRACKLIST_BY_RELEASE_ID, page, releaseId).orElse(null));
    }

    public static List<Map<String, Object>> getTracklist(String mbid, int page) {
        return getEntitiesFromResultSet(getResultSet(TRACKLIST_BY_RELEASE_MBID, page, mbid).orElse(null));
    }

    public static List<Map<String, Object>> getTags(List<Long> releaseGroupIds, int limit) {
        return getEntitiesFromResultSet(getResultSet(TAGS_BY_RELEASE_GROUPS, 0, releaseGroupIds, limit).orElse(null));
    }

    private static Optional<Connection> getConnection() {
        try {
            return Optional.of(INSTANCE.dataSource.getConnection());
        } catch (SQLException e) {
            LOGGER.error("Failed to connect to musicbrainz database.");
            return Optional.empty();
        }
    }

    private static Optional<ResultSet> getResultSet(String query, int page, Object... params) {
        Optional<Connection> optionalConn = getConnection();
        if (optionalConn.isPresent()) {
            Connection conn = optionalConn.get();
            try {
                PreparedStatement ps = conn.prepareStatement(query + " limit ? offset ? ");
                int index = 0;
                for (Object param : params) {
                    index++;
                    if (param instanceof String) {
                        ps.setString(index, (String) param);
                    }
                    if (param instanceof Integer) {
                        ps.setInt(index, (Integer) param);
                    }
                    if (param instanceof Long) {
                        ps.setLong(index, (Long) param);
                    }
                    if (param instanceof List) {
                        ps.setArray(index, conn.createArrayOf("integer", ((List) param).toArray()));
                    }
                    if (param instanceof Boolean) {
                        ps.setBoolean(index, (Boolean) param);
                    }
                }
                ps.setInt(++index, PAGE_SIZE);
                ps.setInt(++index, PAGE_SIZE * page);
                return Optional.of(ps.executeQuery());
            } catch (SQLException e) {
                LOGGER.error("Error executing query", e);
                return Optional.empty();
            } finally {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        } else {
            return Optional.empty();
        }
    }

    private static List<Map<String, Object>> getEntitiesFromResultSet(ResultSet resultSet) {
        ArrayList<Map<String, Object>> entities = new ArrayList<>();
        if (resultSet == null) {
            return entities;
        }
        try {
            while (resultSet.next()) {
                entities.add(getEntityFromResultSet(resultSet));
            }
        } catch (SQLException e) {
            LOGGER.error("Error processing resultSet", e);
        }
        return entities;
    }

    private static Map<String, Object> getEntityFromResultSet(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        Map<String, Object> resultsMap = new HashMap<>();
        for (int i = 1; i <= columnCount; ++i) {
            String columnName = metaData.getColumnName(i).toLowerCase();
            Object object = resultSet.getObject(i);
            resultsMap.put(columnName, object);
        }
        return resultsMap;
    }

}
