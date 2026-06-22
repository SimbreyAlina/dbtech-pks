package de.htwberlin.dbtech.aufgaben.ue03;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import de.htwberlin.dbtech.exceptions.CoolingSystemException;
import de.htwberlin.dbtech.exceptions.DataException;


public class CoolingService implements ICoolingService {

    private Connection connection;

    @Override
    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    private Connection useConnection() {
        if (connection == null) {
            throw new DataException("Connection not set");
        }
        return connection;
    }

    @Override
    public void transferSample(Integer sampleId, Integer diameterInCM) {

        // Gibt es die Probe in der DB?
        if (!isSampleIdExisting(sampleId)) {
            throw new CoolingSystemException("Probe existiert nicht: " + sampleId);
        }

        // Gibt es ueberhaupt ein Tablett mit passendem Durchmesser?
        if (!isTrayWithDiameterExisting(diameterInCM)) {
            throw new CoolingSystemException("Kein Tablett mit Durchmesser: " + diameterInCM);
        }

        // Gibt es ein Tablett mit passendem Durchmesser und freiem Platz?
        if (!isTrayWithFreeSpaceExisting(diameterInCM)) {
            throw new CoolingSystemException("Alle Tabletts mit Durchmesser " + diameterInCM + " sind voll");
        }

        Date sampleExpiration = getSampleExpirationDate(sampleId);

        // Gibt es ein bereits genutztes Tablett mit freiem Platz, dessen
        // Ablaufdatum groesser als das der Probe ist?
        Integer trayId = findNonEmptyTrayWithFreeSpace(diameterInCM, sampleExpiration);

        if (trayId != null) {
            // Probe auf kleinsten freien Platz des Tabletts setzen
            int placeNo = findSmallestFreePlaceNo(trayId);
            insertPlace(trayId, placeNo, sampleId);
        } else {
            // Kein passendes Tablett vorhanden -> leeres Tablett verwenden
            Integer emptyTrayId = findEmptyTrayWithFreeSpace(diameterInCM);
            if (emptyTrayId == null) {
                throw new CoolingSystemException("Kein leeres Tablett mit Durchmesser: " + diameterInCM);
            }
            // Ablaufdatum des Tabletts = Ablaufdatum der Probe + 30 Tage
            setTrayExpirationDate(emptyTrayId, sampleExpiration);
            // Tablett war leer -> Probe auf Platz 1
            insertPlace(emptyTrayId, 1, sampleId);
        }
    }

    /**
     * prueft, ob die Probe in der DB existiert
     *
     * @param sampleId
     *            - der Primaerschluessel der Probe
     * @return true - Probe existiert | false - Probe existiert nicht
     *
     * @author pdohmeie
     * **/
    public boolean isSampleIdExisting(Integer sampleId) {

        PreparedStatement pStmt = null;
        ResultSet rs = null;
        String sql = "Select count(SAMPLEID) as ANZAHL from Sample where SAMPLEID = ?";
        try {

            pStmt = useConnection().prepareStatement(sql);
            pStmt.setInt(1, sampleId);
            rs = pStmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("ANZAHL") > 0;
            } else {

                return false;
            }
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    /**
     * liefert das Ablaufdatum der Probe
     *
     * @param sampleId
     *            - der Primaerschluessel der Probe
     * @return das Ablaufdatum der Probe
     */
    private Date getSampleExpirationDate(Integer sampleId) {

        PreparedStatement pStmt = null;
        ResultSet rs = null;
        String sql = "Select EXPIRATIONDATE from Sample where SAMPLEID = ?";
        try {
            pStmt = useConnection().prepareStatement(sql);
            pStmt.setInt(1, sampleId);
            rs = pStmt.executeQuery();
            if (rs.next()) {
                return rs.getDate("EXPIRATIONDATE");
            } else {
                throw new CoolingSystemException("Kein Ablaufdatum fuer Probe: " + sampleId);
            }
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    /**
     * prueft, ob ein Tablett mit dem angegebenen Durchmesser existiert
     *
     * @param diameterInCM
     *            - der gesuchte Durchmesser
     * @return true - Tablett existiert | false - Tablett existiert nicht
     */
    private boolean isTrayWithDiameterExisting(Integer diameterInCM) {

        PreparedStatement pStmt = null;
        ResultSet rs = null;
        String sql = "Select count(*) as ANZAHL from Tray where DIAMETERINCM = ?";
        try {
            pStmt = useConnection().prepareStatement(sql);
            pStmt.setInt(1, diameterInCM);
            rs = pStmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("ANZAHL") > 0;
            } else {
                return false;
            }
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    /**
     * prueft, ob ein Tablett mit dem angegebenen Durchmesser existiert, das
     * noch einen freien Platz hat.
     *
     * Query vom Prof: zaehlt pro Tablett (RIGHT JOIN, damit auch leere
     * Tabletts mit anzahl_proben = 0 erfasst werden) die belegten Plaetze
     * und filtert auf Tabletts, bei denen capacity - anzahl_proben > 0 ist.
     *
     * @param diameterInCM
     *            - der gesuchte Durchmesser
     * @return true - es gibt ein Tablett mit freiem Platz | false - alle
     *         passenden Tabletts sind voll
     */
    private boolean isTrayWithFreeSpaceExisting(Integer diameterInCM) {

        PreparedStatement pStmt = null;
        ResultSet rs = null;
        String sql =
                "SELECT " +
                        "    capacity - anzahl_proben AS anzahl_freie_plaetze " +
                        "FROM ( " +
                        "    SELECT " +
                        "        COUNT(p.sampleid) AS anzahl_proben, " +
                        "        t.trayid, " +
                        "        t.capacity, " +
                        "        t.expirationdate " +
                        "    FROM " +
                        "        place p " +
                        "        RIGHT JOIN tray t ON p.trayid = t.trayid " +
                        "    WHERE " +
                        "        t.diameterincm = ? " +
                        "    GROUP BY " +
                        "        t.trayid, " +
                        "        t.capacity, " +
                        "        t.expirationdate " +
                        ") " +
                        "WHERE " +
                        "    capacity - anzahl_proben > 0";
        try {
            pStmt = useConnection().prepareStatement(sql);
            pStmt.setInt(1, diameterInCM);
            rs = pStmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    /**
     * sucht ein bereits genutztes Tablett (EXPIRATIONDATE gesetzt) mit
     * passendem Durchmesser, freiem Platz und einem Ablaufdatum, das groesser
     * als das Ablaufdatum der Probe ist. Es wird das Tablett mit dem
     * kleinsten passenden Ablaufdatum gewaehlt.
     *
     * @param diameterInCM
     *            - der gesuchte Durchmesser
     * @param sampleExpiration
     *            - das Ablaufdatum der Probe
     * @return die TrayId des gefundenen Tabletts oder null, falls keines
     *         passt
     */
    private Integer findNonEmptyTrayWithFreeSpace(Integer diameterInCM, Date sampleExpiration) {

        PreparedStatement pStmt = null;
        ResultSet rs = null;
        String sql =
                "SELECT t.TRAYID FROM Tray t " +
                        "WHERE t.DIAMETERINCM = ? " +
                        "  AND t.EXPIRATIONDATE IS NOT NULL " +
                        "  AND t.EXPIRATIONDATE > ? " +
                        "  AND (SELECT COUNT(*) FROM Place p WHERE p.TRAYID = t.TRAYID) < t.CAPACITY " +
                        "ORDER BY t.EXPIRATIONDATE ASC " +
                        "FETCH FIRST 1 ROWS ONLY";
        try {
            pStmt = useConnection().prepareStatement(sql);
            pStmt.setInt(1, diameterInCM);
            pStmt.setDate(2, sampleExpiration);
            rs = pStmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("TRAYID");
            } else {
                return null;
            }
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    /**
     * sucht ein leeres Tablett (EXPIRATIONDATE IS NULL, keine Places) mit
     * passendem Durchmesser.
     *
     * @param diameterInCM
     *            - der gesuchte Durchmesser
     * @return die TrayId des gefundenen leeren Tabletts oder null, falls
     *         keines existiert
     */
    private Integer findEmptyTrayWithFreeSpace(Integer diameterInCM) {

        PreparedStatement pStmt = null;
        ResultSet rs = null;
        String sql =
                "SELECT TRAYID FROM Tray " +
                        "WHERE DIAMETERINCM = ? " +
                        "  AND EXPIRATIONDATE IS NULL " +
                        "  AND (SELECT COUNT(*) FROM Place p WHERE p.TRAYID = Tray.TRAYID) = 0 " +
                        "ORDER BY TRAYID ASC " +
                        "FETCH FIRST 1 ROWS ONLY";
        try {
            pStmt = useConnection().prepareStatement(sql);
            pStmt.setInt(1, diameterInCM);
            rs = pStmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("TRAYID");
            } else {
                return null;
            }
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    /**
     * setzt das Ablaufdatum des Tabletts auf das Ablaufdatum der Probe plus
     * 30 Tage. Die Addition erfolgt in Oracle SQL, damit es kein
     * Java-Zeitzonenproblem gibt.
     *
     * @param trayId
     *            - der Primaerschluessel des Tabletts
     * @param sampleExpiration
     *            - das Ablaufdatum der Probe
     */
    private void setTrayExpirationDate(Integer trayId, Date sampleExpiration) {

        PreparedStatement pStmt = null;
        String sql = "UPDATE Tray SET EXPIRATIONDATE = TRUNC(? + 30) WHERE TRAYID = ?";
        try {
            pStmt = useConnection().prepareStatement(sql);
            pStmt.setDate(1, sampleExpiration);
            pStmt.setInt(2, trayId);
            pStmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    /**
     * findet den kleinsten freien Platz (PlaceNo) auf einem Tablett. Luecken
     * werden gefuellt, z.B. belegt: 1,2,4 -> Ergebnis 3.
     *
     * @param trayId
     *            - der Primaerschluessel des Tabletts
     * @return die kleinste freie PlaceNo
     */
    private int findSmallestFreePlaceNo(Integer trayId) {

        PreparedStatement pStmt = null;
        ResultSet rs = null;
        String sql = "SELECT PLACENO FROM Place WHERE TRAYID = ? ORDER BY PLACENO ASC";
        try {
            pStmt = useConnection().prepareStatement(sql);
            pStmt.setInt(1, trayId);
            rs = pStmt.executeQuery();
            int expected = 1;
            while (rs.next()) {
                int current = rs.getInt("PLACENO");
                if (current != expected) {
                    return expected; // Luecke gefunden
                }
                expected++;
            }
            return expected; // naechste freie Stelle am Ende
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    /**
     * fuegt einen neuen Place-Datensatz ein.
     *
     * @param trayId
     *            - der Primaerschluessel des Tabletts
     * @param placeNo
     *            - die Platznummer auf dem Tablett
     * @param sampleId
     *            - der Primaerschluessel der Probe
     */
    private void insertPlace(Integer trayId, int placeNo, Integer sampleId) {

        PreparedStatement pStmt = null;
        String sql = "INSERT INTO Place (TRAYID, PLACENO, SAMPLEID) VALUES (?, ?, ?)";
        try {
            pStmt = useConnection().prepareStatement(sql);
            pStmt.setInt(1, trayId);
            pStmt.setInt(2, placeNo);
            pStmt.setInt(3, sampleId);
            pStmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

}