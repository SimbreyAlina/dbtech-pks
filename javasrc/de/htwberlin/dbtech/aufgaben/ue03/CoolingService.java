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

        // SCHRITT 1: Existiert die Probe?
        if (!isSampleIdExisting(sampleId)) {
            throw new CoolingSystemException("Probe existiert nicht: " + sampleId);
        }

        // SCHRITT 2: Gibt es überhaupt ein Tablett mit passendem Durchmesser?
        if (!isTrayWithDiameterExisting(diameterInCM)) {
            throw new CoolingSystemException("Kein Tablett mit Durchmesser: " + diameterInCM);
        }

        // SCHRITT 3: Gibt es ein Tablett mit freien Plätzen und passendem Durchmesser?
        if (!isTrayWithFreeSpaceExisting(diameterInCM)) {
            throw new CoolingSystemException("Alle Tabletts mit Durchmesser " + diameterInCM + " sind voll");
        }

        // Ab hier: Es gibt mindestens ein Tablett mit freiem Platz
        Date sampleExpiration = getSampleExpirationDate(sampleId);

        // SCHRITT 4: Ist das Ablaufdatum für ein passendes Tablett bereits gesetzt?
        //            d.h. gibt es ein nicht-leeres Tablett, dessen ExpirationDate > sampleExpiration?
        Integer trayId = findNonEmptyTrayWithFreeSpace(diameterInCM, sampleExpiration);

        if (trayId != null) {
            // JA: Probe auf kleinsten freien Platz des Tabletts setzen
            int placeNo = findSmallestFreePlaceNo(trayId);
            insertPlace(trayId, placeNo, sampleId);
        } else {
            // NEIN: Leeres Tablett (EXPIRATIONDATE IS NULL) nehmen
            Integer emptyTrayId = findEmptyTrayWithFreeSpace(diameterInCM);
            if (emptyTrayId == null) {
                throw new CoolingSystemException("Kein leeres Tablett mit Durchmesser: " + diameterInCM);
            }
            // Ablaufdatum setzen: Ablaufdatum Probe + 30 Tage (Berechnung in Oracle, kein Zeitzonenproblem)
            setTrayExpirationDate(emptyTrayId, sampleExpiration);
            // Probe auf Platz 1 setzen (Tablett war leer)
            insertPlace(emptyTrayId, 1, sampleId);
        }
    }


    // SCHRITT 1: Probe prüfen


    /**
     * Prüft ob eine Probe mit der gegebenen ID in der DB existiert.
     */
    public boolean isSampleIdExisting(Integer sampleId) {
        String sql = "SELECT COUNT(SAMPLEID) AS ANZAHL FROM Sample WHERE SAMPLEID = ?";
        try (PreparedStatement ps = useConnection().prepareStatement(sql)) {
            ps.setInt(1, sampleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("ANZAHL") > 0;
            }
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    /**
     * Liefert das Ablaufdatum der Probe.
     */
    private Date getSampleExpirationDate(Integer sampleId) {
        String sql = "SELECT EXPIRATIONDATE FROM Sample WHERE SAMPLEID = ?";
        try (PreparedStatement ps = useConnection().prepareStatement(sql)) {
            ps.setInt(1, sampleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDate("EXPIRATIONDATE");
                }
                throw new CoolingSystemException("Kein Ablaufdatum fuer Probe: " + sampleId);
            }
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    // SCHRITT 2: Gibt es überhaupt ein Tablett mit passendem Durchmesser?


    private boolean isTrayWithDiameterExisting(Integer diameterInCM) {
        String sql = "SELECT COUNT(*) AS ANZAHL FROM Tray WHERE DIAMETERINCM = ?";
        try (PreparedStatement ps = useConnection().prepareStatement(sql)) {
            ps.setInt(1, diameterInCM);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("ANZAHL") > 0;
            }
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }


    // SCHRITT 3: Gibt es ein Tablett mit freien Plätzen?


    private boolean isTrayWithFreeSpaceExisting(Integer diameterInCM) {
        String sql =
                "SELECT COUNT(*) AS ANZAHL FROM Tray t " +
                        "WHERE t.DIAMETERINCM = ? " +
                        "  AND (SELECT COUNT(*) FROM Place p WHERE p.TRAYID = t.TRAYID) < t.CAPACITY";
        try (PreparedStatement ps = useConnection().prepareStatement(sql)) {
            ps.setInt(1, diameterInCM);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("ANZAHL") > 0;
            }
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }


    // SCHRITT 4a: Nicht-leeres Tablett mit freiem Platz und passendem Ablaufdatum


    /**
     * Sucht das Tablett mit:
     *   - passendem Durchmesser
     *   - EXPIRATIONDATE bereits gesetzt (nicht NULL)
     *   - EXPIRATIONDATE > Ablaufdatum der Probe
     *   - noch freien Plätzen
     * Nimmt das Tablett mit dem KLEINSTEN solchen Ablaufdatum.
     */
    private Integer findNonEmptyTrayWithFreeSpace(Integer diameterInCM, Date sampleExpiration) {
        String sql =
                "SELECT t.TRAYID FROM Tray t " +
                        "WHERE t.DIAMETERINCM = ? " +
                        "  AND t.EXPIRATIONDATE IS NOT NULL " +
                        "  AND t.EXPIRATIONDATE > ? " +
                        "  AND (SELECT COUNT(*) FROM Place p WHERE p.TRAYID = t.TRAYID) < t.CAPACITY " +
                        "ORDER BY t.EXPIRATIONDATE ASC " +
                        "FETCH FIRST 1 ROWS ONLY";
        try (PreparedStatement ps = useConnection().prepareStatement(sql)) {
            ps.setInt(1, diameterInCM);
            ps.setDate(2, sampleExpiration);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("TRAYID");
                return null;
            }
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    // SCHRITT 4b: Leeres Tablett (EXPIRATIONDATE IS NULL)


    /**
     * Sucht ein leeres Tablett (EXPIRATIONDATE IS NULL, keine Places) mit passendem Durchmesser.
     */
    private Integer findEmptyTrayWithFreeSpace(Integer diameterInCM) {
        String sql =
                "SELECT TRAYID FROM Tray " +
                        "WHERE DIAMETERINCM = ? " +
                        "  AND EXPIRATIONDATE IS NULL " +
                        "  AND (SELECT COUNT(*) FROM Place p WHERE p.TRAYID = Tray.TRAYID) = 0 " +
                        "ORDER BY TRAYID ASC " +
                        "FETCH FIRST 1 ROWS ONLY";
        try (PreparedStatement ps = useConnection().prepareStatement(sql)) {
            ps.setInt(1, diameterInCM);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("TRAYID");
                return null;
            }
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    /**
     * Setzt das Ablaufdatum des Tabletts auf Probe-Ablaufdatum + 30 Tage.
     * Addition in Oracle SQL → kein Java-Zeitzonenproblem.
     */
    private void setTrayExpirationDate(Integer trayId, Date sampleExpiration) {
        String sql = "UPDATE Tray SET EXPIRATIONDATE = TRUNC(? + 30) WHERE TRAYID = ?";
        try (PreparedStatement ps = useConnection().prepareStatement(sql)) {
            ps.setDate(1, sampleExpiration);
            ps.setInt(2, trayId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    // HILFSMETHODEN: Platz finden und einfügen


    /**
     * Findet den kleinsten freien PlaceNo auf einem Tablett.
     * Lücken werden gefüllt: z.B. belegt: 1,2,4 → gibt 3 zurück.
     */
    private int findSmallestFreePlaceNo(Integer trayId) {
        String sql = "SELECT PLACENO FROM Place WHERE TRAYID = ? ORDER BY PLACENO ASC";
        try (PreparedStatement ps = useConnection().prepareStatement(sql)) {
            ps.setInt(1, trayId);
            try (ResultSet rs = ps.executeQuery()) {
                int expected = 1;
                while (rs.next()) {
                    int current = rs.getInt("PLACENO");
                    if (current != expected) {
                        return expected; // Lücke gefunden
                    }
                    expected++;
                }
                return expected; // nächste freie Stelle am Ende
            }
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    /**
     * Fügt einen neuen Place-Datensatz ein.
     */
    private void insertPlace(Integer trayId, int placeNo, Integer sampleId) {
        String sql = "INSERT INTO Place (TRAYID, PLACENO, SAMPLEID) VALUES (?, ?, ?)";
        try (PreparedStatement ps = useConnection().prepareStatement(sql)) {
            ps.setInt(1, trayId);
            ps.setInt(2, placeNo);
            ps.setInt(3, sampleId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }
}