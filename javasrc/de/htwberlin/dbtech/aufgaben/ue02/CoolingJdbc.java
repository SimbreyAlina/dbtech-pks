package de.htwberlin.dbtech.aufgaben.ue02;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.htwberlin.dbtech.exceptions.CoolingSystemException;
import de.htwberlin.dbtech.exceptions.DataException;

import java.sql.Date;

public class CoolingJdbc implements ICoolingJdbc {

    private static final Logger L = LoggerFactory.getLogger(CoolingJdbc.class);
    private Connection connection;

    @Override
    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    @SuppressWarnings("unused")
    private Connection useConnection() {
        if (connection == null) {
            throw new DataException("Connection not set");
        }
        return connection;
    }

    @Override
    public List<String> getSampleKinds() {

        // wird an sql-Statement übergeben, um die SampleKinds zu holen
        PreparedStatement pStmt = null;

        // enthält später das Ergebnis der SQL-Abfrage
        ResultSet rs = null;

        // Liste welche alle SampleKinds enthält
        List<String> sampleKind = null;

        try {

            String sql = "SELECT text FROM SampleKind ORDER BY text ASC";

            sampleKind = new LinkedList<String>();

            // PreparedStatement erstellen
            pStmt = useConnection().prepareStatement(sql);

            // SQL-Abfrage ausführen
            rs = pStmt.executeQuery();

            // Ergebnisse in die Liste einfügen
            while (rs.next()) {
                sampleKind.add(rs.getString("text"));
            }

        } catch (SQLException e) {
            throw new DataException(e);
        }

        return sampleKind;
    }
    //Aufgabe 01
    @Override
    public Sample findSampleById(Integer sampleId) {
        L.info("findSampleById: sampleId: " + sampleId);

        String sql = "SELECT SampleID, SampleKindID, ExpirationDate " +
                "FROM Sample " +
                "WHERE SampleID = ?";

        try (PreparedStatement pStmt = useConnection().prepareStatement(sql)) {
            pStmt.setInt(1, sampleId);

            try (ResultSet rs = pStmt.executeQuery()) {
                if (rs.next()) {
                    java.sql.Date dbDate = rs.getDate("ExpirationDate");

                    LocalDate expirationLocalDate = (dbDate != null) ? dbDate.toLocalDate() : null;

                    Sample sample = new Sample(
                            rs.getInt("SampleID"),
                            rs.getInt("SampleKindID"),
                            expirationLocalDate
                    );

                    return sample;
                }
            }
        } catch (SQLException e) {
            L.error("Fehler beim Abrufen der SampleID " + sampleId + ": " + e.getMessage());
            throw new DataException(e);
        }

        throw new CoolingSystemException("SampleID does not exist: " + sampleId);
    }

    // Aufgabe 02
    @Override
    public void createSample(Integer sampleId, Integer sampleKindId) {

        L.info("createSample: sampleId: " + sampleId +
                ", sampleKindId: " + sampleKindId);

        // Prüft ob SampleID bereits existiert
        String checkSampleSql =
                "SELECT SampleID FROM Sample WHERE SampleID = ?";

        // Holt ValidNoOfDays aus SampleKind
        String getKindSql =
                "SELECT ValidNoOfDays FROM SampleKind WHERE SampleKindID = ?";

        // Fügt neue Probe ein
        String insertSql =
                "INSERT INTO Sample (SampleID, SampleKindID, ExpirationDate) VALUES (?, ?, ?)";

        try {

            // Prüfen ob SampleID schon existiert
            PreparedStatement checkStmt =
                    useConnection().prepareStatement(checkSampleSql);

            checkStmt.setInt(1, sampleId);

            ResultSet rsSample = checkStmt.executeQuery();

            if (rsSample.next()) {
                throw new CoolingSystemException("SampleID already exists");
            }

            // Prüfen ob SampleKindID existiert
            PreparedStatement kindStmt =
                    useConnection().prepareStatement(getKindSql);

            kindStmt.setInt(1, sampleKindId);

            ResultSet rsKind = kindStmt.executeQuery();

            if (!rsKind.next()) {
                throw new CoolingSystemException("SampleKindID does not exist");
            }

            // Anzahl gültiger Tage holen
            int validNoOfDays = rsKind.getInt("ValidNoOfDays");

            // Ablaufdatum berechnen
            Date expirationDate =
                    Date.valueOf(java.time.LocalDate.now().plusDays(validNoOfDays));

            // INSERT vorbereiten
            PreparedStatement insertStmt =
                    useConnection().prepareStatement(insertSql);

            insertStmt.setInt(1, sampleId);
            insertStmt.setInt(2, sampleKindId);
            insertStmt.setDate(3, expirationDate);

            // Datensatz einfügen
            insertStmt.executeUpdate();

        } catch (SQLException e) {

            throw new DataException(e);
        }
    }

    // Aufgabe 03
    @Override
    public void clearTray(Integer trayId) {

        L.info("clearTray: trayId: " + trayId);

        // Prüft ob Tray existiert
        String checkTraySql =
                "SELECT TrayID FROM Tray WHERE TrayID = ?";

        // Holt alle SampleIDs, die auf dem Tray liegen
        String selectSampleSql =
                "SELECT SampleID FROM Place WHERE TrayID = ?";

        // Löscht die Zuordnungen aus Place
        String deletePlaceSql =
                "DELETE FROM Place WHERE TrayID = ?";

        // Löscht die Samples
        String deleteSampleSql =
                "DELETE FROM Sample WHERE SampleID = ?";

        // Setzt das ExpirationDate des Trays auf NULL
        String updateTraySql =
                "UPDATE Tray SET ExpirationDate = NULL WHERE TrayID = ?";

        try {

            // Prüfen ob Tray existiert
            PreparedStatement checkStmt =
                    useConnection().prepareStatement(checkTraySql);

            checkStmt.setInt(1, trayId);

            ResultSet rsTray = checkStmt.executeQuery();

            if (!rsTray.next()) {
                throw new CoolingSystemException("TrayID does not exist");
            }

            // SampleIDs merken, bevor Place gelöscht wird
            List<Integer> sampleIds = new LinkedList<Integer>();

            PreparedStatement selectSampleStmt =
                    useConnection().prepareStatement(selectSampleSql);

            selectSampleStmt.setInt(1, trayId);

            ResultSet rsSamples = selectSampleStmt.executeQuery();

            while (rsSamples.next()) {
                sampleIds.add(rsSamples.getInt("SampleID"));
            }

            // Erst Place-Einträge löschen
            PreparedStatement deletePlaceStmt =
                    useConnection().prepareStatement(deletePlaceSql);

            deletePlaceStmt.setInt(1, trayId);

            deletePlaceStmt.executeUpdate();

            // Dann nur die Samples löschen, die wirklich auf diesem Tray lagen
            PreparedStatement deleteSampleStmt =
                    useConnection().prepareStatement(deleteSampleSql);

            for (Integer sampleId : sampleIds) {
                deleteSampleStmt.setInt(1, sampleId);
                deleteSampleStmt.executeUpdate();
            }

            // Tray "leeren" -> ExpirationDate auf NULL setzen
            PreparedStatement updateTrayStmt =
                    useConnection().prepareStatement(updateTraySql);

            updateTrayStmt.setInt(1, trayId);

            updateTrayStmt.executeUpdate();

        } catch (SQLException e) {

            throw new DataException(e);
        }
    }
}