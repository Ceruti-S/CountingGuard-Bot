package it.codhub.countingGuard;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.awt.Color;
import java.sql.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class MainClass extends ListenerAdapter
{

    private final HikariDataSource dataSource;
    private final AtomicLong ultimoNumero = new AtomicLong(0);
    private final AtomicReference<String> idCanaleLog = new AtomicReference<>("1490445834657988799");
    private final AtomicReference<String> idCanaleCounting = new AtomicReference<>("1490444436436680866");
    private final Set<String> blacklistCache = new HashSet<>(); //cache veloce per gestire migliaia di msg/min

    private final ExecutorService dbExecutor = Executors.newFixedThreadPool(10);

    private final List<String> ID_RUOLI = List.of(
            "1491048983357952191", //contabile alle prime armi
            "1491049589384413194", //esattore di numeri
            "1491049733417074749",  //magnate delle cifre
            "1491049828724117654", //architetto del calcolo
            "1491049989366087782" //imperatore dell'infinito
    );

    private final List<Long> MILESTONES = List.of(10L, 50L, 100L, 250L, 500L, 1000L, 5000L, 10000L, 100000L, 1000000L);

    private final AtomicReference<String> ultimoUtenteID = new AtomicReference<>("");

    public MainClass()
    {

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:database_elitario.db?journal_mode=WAL&synchronous=NORMAL");

        config.setMaximumPoolSize(5);

        //aumento il tempo di attesa prima di dare errore (30 secondi)
        config.addDataSourceProperty("config.busy_timeout", "30000");

        this.dataSource = new HikariDataSource(config);
        inizializzaDatabase();

        this.ultimoNumero.set(caricaDatoGlobaleSingolo("ultimo_numero"));
        String logSalvato = caricaStringaGlobale("canale_log");
        if (logSalvato != null) this.idCanaleLog.set(logSalvato);
        caricaBlacklist();

        String countingSalvato = caricaStringaGlobale("canale_counting");
        if (countingSalvato != null)
            this.idCanaleCounting.set(countingSalvato);

    }

    public static void main(String[] args)
    {

        String token = System.getenv("COUNTING_TOKEN");
        if (token == null)
        {

            System.err.println("ERRORE: Variabile d'ambiente COUNTING_TOKEN non trovata!");
            return;

        }

        var jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS)
                .addEventListeners(new MainClass())
                .build();

        jda.updateCommands().addCommands(
                Commands.slash("rank", "Guarda il tuo punteggio personale"),
                Commands.slash("stats", "Guarda i tuoi punti e i reset causati"),
                Commands.slash("top", "Mostra i primi 10 utenti con più punti"),
                Commands.slash("record", "Mostra il record massimo del server"),

                //comandi admin
                Commands.slash("set-count", "Imposta il numero attuale del conteggio")
                        .addOption(OptionType.INTEGER, "numero", "Nuovo numero", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),

                Commands.slash("reset-user", "Azzera statistiche di un utente")
                        .addOption(OptionType.USER, "utente", "Utente da resettare", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),

                Commands.slash("set-points", "Imposta i punti di un utente")
                        .addOption(OptionType.USER, "utente", "Utente target", true)
                        .addOption(OptionType.INTEGER, "punti", "Nuovo punteggio", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),

                Commands.slash("blacklist", "Aggiunge/Rimuove un utente dalla blacklist")
                        .addOption(OptionType.USER, "utente", "Utente da gestire", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),

                Commands.slash("set-log-channel", "Imposta il canale per i log dello staff")
                        .addOption(OptionType.CHANNEL, "canale", "Canale testuale", true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),

                Commands.slash("set-counting-channel", "Imposta il canale dove si conta")
                        .addOption(OptionType.CHANNEL, "canale", "Il canale dedicato", true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),

                Commands.slash("admin-stats", "Visualizza il profilo tecnico completo di un utente")
                        .addOption(OptionType.USER, "utente", "Utente da ispezionare", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),

                Commands.slash("elimina-dati-utente", "Elimina definitivamente i dati di un utente su questo bot")
                        .addOption(OptionType.USER, "utente", "Utente di cui vuoi eliminare i dati", true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))

        ).queue();

    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event)
    {

        event.deferReply(true).queue();

        inviaLogComando(event);

        dbExecutor.submit(() ->
        {

            switch (event.getName())
            {

                case "rank" -> gestisciRank(event);
                case "stats" -> gestisciStats(event);
                case "top" -> gestisciTop(event);
                case "record" -> gestisciRecord(event);
                //admin
                case "set-count" -> adminSetCount(event);
                case "reset-user" -> adminResetUser(event);
                case "set-points" -> adminSetPoints(event);
                case "blacklist" -> adminBlacklist(event);
                case "set-log-channel" -> adminSetLogChannel(event);
                case "set-counting-channel" -> adminSetCountingChannel(event);
                case "admin-stats" -> adminStats(event);
                case "elimina-dati-utente" -> eliminaDati(event);

            }

        });

    }

    // --- LOGICA ADMIN ---

    private void eliminaDati(SlashCommandInteractionEvent event)
    {

        var targetUser = event.getOption("utente").getAsUser();
        String uid = targetUser.getId();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM utenti WHERE id = ?"))
        {

            ps.setString(1, uid);
            int rowsDeleted = ps.executeUpdate();

            if (rowsDeleted > 0)
            {

                event.getHook().editOriginal("I dati di " + targetUser.getAsMention() + " sono stati eliminati definitivamente dal database.").queue();

            }
            else
            {

                event.getHook().editOriginal("L'utente " + targetUser.getAsMention() + " non aveva dati registrati nel database.").queue();

            }

        }
        catch (SQLException e)
        {

            e.printStackTrace();
            event.getHook().editOriginal("Errore durante l'eliminazione dei dati dal database.").queue();

        }

    }

    private void adminStats(SlashCommandInteractionEvent event)
    {

        var target = event.getOption("utente").getAsUser();
        String sql = "SELECT * FROM utenti WHERE id = ?";

        try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql))
        {

            pstmt.setString(1, target.getId());
            ResultSet rs = pstmt.executeQuery();

            if (rs.next())
            {

                int punti = rs.getInt("punti");
                int errori = rs.getInt("errori");

                EmbedBuilder eb = new EmbedBuilder()
                        .setTitle("Scheda Tecnica: " + target.getName())
                        .setThumbnail(target.getEffectiveAvatarUrl())
                        .setColor(Color.RED)
                        .setDescription("Dati grezzi estratti dal database per l'amministrazione.")
                        .addField("User ID", "`" + target.getId() + "`", false)
                        .addField("Punti Attuali", "**" + punti + "**", true)
                        .addField("Reset Totali Causati", "**" + errori + "**", true)
                        .addField("Status Blacklist", (blacklistCache.contains(target.getId()) ? "In Blacklist" : "Pulito"), true)
                        .setFooter("Richiesto da: " + event.getUser().getAsTag())
                        .setTimestamp(java.time.Instant.now());

                event.getHook().editOriginalEmbeds(eb.build()).queue();

            }
            else
            {

                event.getHook().editOriginal("L'utente <@" + target.getId() + "> non è presente nel database (non ha mai scritto numeri).").queue();

            }

        }
        catch (SQLException e)
        {

            e.printStackTrace();

        }

    }

    private void adminSetCount(SlashCommandInteractionEvent event)
    {

        long num = event.getOption("numero").getAsLong();
        ultimoNumero.set(num);
        salvaDatoGlobaleSingolo("ultimo_numero", num);
        event.getHook().editOriginal("Conteggio impostato a **" + num + "**.").queue();

    }

    private void adminResetUser(SlashCommandInteractionEvent event)
    {

        String uid = event.getOption("utente").getAsUser().getId();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE utenti SET punti = 0, errori = 0 WHERE id = ?"))
        {

            ps.setString(1, uid);
            ps.executeUpdate();
            event.getHook().editOriginal("Statistiche di <@" + uid + "> resettate.").queue();

        }
        catch (SQLException e)
        {

            e.printStackTrace();

        }

    }

    private void adminSetPoints(SlashCommandInteractionEvent event)
    {

        String uid = event.getOption("utente").getAsUser().getId();
        int punti = event.getOption("punti").getAsInt();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO utenti(id, punti, errori) VALUES(?, ?, 0) ON CONFLICT(id) DO UPDATE SET punti = ?"))
        {

            ps.setString(1, uid);
            ps.setInt(2, punti);
            ps.setInt(3, punti);
            ps.executeUpdate();
            event.getHook().editOriginal("Punti di <@" + uid + "> impostati a **" + punti + "**.").queue();

        }
        catch (SQLException e)
        {

            e.printStackTrace();

        }

    }

    private void adminBlacklist(SlashCommandInteractionEvent event)
    {

        String uid = event.getOption("utente").getAsUser().getId();
        boolean rimosso;

        try (Connection conn = dataSource.getConnection())
        {

            if (blacklistCache.contains(uid))
            {

                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM blacklist WHERE id = ?"))
                {

                    ps.setString(1, uid);
                    ps.executeUpdate();

                }
                blacklistCache.remove(uid);
                rimosso = true;

            }
            else
            {

                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO blacklist(id) VALUES(?)"))
                {

                    ps.setString(1, uid);
                    ps.executeUpdate();

                }
                blacklistCache.add(uid);
                rimosso = false;

            }

            event.getHook().editOriginal(rimosso ? "Utente rimosso dalla blacklist." : "Utente aggiunto alla blacklist.").queue();

        }
        catch (SQLException e)
        {

            e.printStackTrace();

        }

    }

    private void adminSetLogChannel(SlashCommandInteractionEvent event)
    {

        if (event.getOption("canale").getChannelType() != ChannelType.TEXT)
        {

            event.getHook().editOriginal("Seleziona un canale testuale!").queue();
            return;

        }
        String cid = event.getOption("canale").getAsChannel().getId();
        idCanaleLog.set(cid);
        salvaStringaGlobale("canale_log", cid);
        event.getHook().editOriginal("Canale log impostato a <#" + cid + ">.").queue();

    }

    private void adminSetCountingChannel(SlashCommandInteractionEvent event)
    {

        String cid = event.getOption("canale").getAsChannel().getId();
        idCanaleCounting.set(cid);
        salvaStringaGlobale("canale_counting", cid);
        event.getHook().editOriginal("Canale di conteggio impostato su <#" + cid + ">! Ora il bot lavorerà solo lì.").queue();

    }

    // --- LOGICA CORE ---

    @Override
    public void onMessageReceived(MessageReceivedEvent event)
    {

        //ignora i bot (incluso se stesso) senza fare nulla
        if (event.getAuthor().isBot()) return;

        if (idCanaleCounting.get().isEmpty() || !event.getChannel().getId().equals(idCanaleCounting.get()))
        {

            return;

        }

        //se l'utente è blacklistato, elimino il messaggio e chiudo
        if (blacklistCache.contains(event.getAuthor().getId()))
        {

            event.getMessage().delete().queue(null, err -> {});
            return;

        }

        String msg = event.getMessage().getContentRaw().trim();
        if (msg.matches("\\d+"))
        {

            long ricevuto = Long.parseLong(msg);
            long atteso = ultimoNumero.get() + 1;

            if (event.getAuthor().getId().equals(ultimoUtenteID.get()))
            {

                event.getMessage().delete().queue();
                event.getChannel().sendMessage(event.getAuthor().getAsMention() + ", non puoi contare due volte di fila!").queue(m -> m.delete().queueAfter(3, TimeUnit.SECONDS));
                return;
            }

            if (ricevuto == atteso)
            {

                ultimoNumero.set(ricevuto);
                event.getMessage().addReaction(Emoji.fromUnicode("\u2705")).queue();
                if (MILESTONES.contains(ricevuto))
                {

                    event.getChannel().sendMessage("Milestone raggiunta: **" + ricevuto + "**!").queue();

                }
                dbExecutor.submit(() -> aggiornaDati(event, event.getAuthor().getId(), ricevuto, true));
                ultimoUtenteID.set(event.getAuthor().getId());

            }
            else
            {

                eseguiReset(event, "Errore numero", msg);

            }

        }
        else if (!event.getMessage().getContentRaw().startsWith("/"))
        {

            gestisciTestoNonValido(event, msg);

        }

    }

    private void inviaLogComando(SlashCommandInteractionEvent event)
    {

        TextChannel logChannel = event.getGuild().getTextChannelById(idCanaleLog.get());
        if (logChannel == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("`").append("/").append(event.getName()).append("` ");

        event.getOptions().forEach(opt -> {
            sb.append("[").append(opt.getName()).append(": ").append(opt.getAsString()).append("] ");
        });

        EmbedBuilder lb = new EmbedBuilder()
                .setTitle("Esecuzione Comando")
                .setColor(Color.GRAY)
                .addField("Utente", event.getUser().getAsTag() + " (" + event.getUser().getAsMention() + ")", true)
                .addField("Comando", sb.toString(), true)
                .addField("Canale", event.getChannel().getAsMention(), true)
                .setTimestamp(java.time.Instant.now());

        logChannel.sendMessageEmbeds(lb.build()).queue();

    }

    private void eseguiReset(MessageReceivedEvent event, String motivo, String inviato)
    {

        long fallito = ultimoNumero.get();
        ultimoNumero.set(0);
        event.getMessage().delete().queue(null, err -> {});
        event.getChannel().sendMessage("**RESET!** " + event.getAuthor().getAsMention() + " ha sbagliato a **" + fallito + "**!\nSi ricomincia da 1. (Penalità: -10 punti)").queue();
        dbExecutor.submit(() -> {
            aggiornaDati(event, event.getAuthor().getId(), 0, false);
            inviaLogStaff(event, inviato, fallito + 1);
        });

    }

    private void aggiornaDati(MessageReceivedEvent event, String userId, long numero, boolean successo)
    {

        try (Connection conn = dataSource.getConnection())
        {

            conn.setAutoCommit(false);
            int puntiAttuali = 0;
            try
            {

                if (successo)
                {

                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO utenti(id, punti, errori) VALUES(?, 1, 0) ON CONFLICT(id) DO UPDATE SET punti = punti + 1"))
                    {

                        ps.setString(1, userId);
                        ps.executeUpdate();

                    }

                    aggiornaConfigurazione(conn, "ultimo_numero", numero);
                    long rec = caricaDatoConfigurazione(conn, "record_globale");
                    if (numero > rec) aggiornaConfigurazione(conn, "record_globale", numero);

                }
                else
                {

                    try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO utenti(id, punti, errori) VALUES(?, 0, 0)"))
                    {

                        ps.setString(1, userId); ps.executeUpdate();

                    }

                    try (PreparedStatement ps = conn.prepareStatement("UPDATE utenti SET punti = MAX(0, punti - 10), errori = errori + 1 WHERE id = ?"))
                    {

                        ps.setString(1, userId); ps.executeUpdate();

                    }
                    aggiornaConfigurazione(conn, "ultimo_numero", 0);

                }

                try (PreparedStatement ps = conn.prepareStatement("SELECT punti FROM utenti WHERE id = ?"))
                {

                    ps.setString(1, userId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) puntiAttuali = rs.getInt("punti");

                }
                conn.commit();
                controllaEAssegnaRuoli(event, puntiAttuali);

            }
            catch (SQLException e)
            {

                conn.rollback(); e.printStackTrace();

            }
            finally
            {

                conn.setAutoCommit(true);

            }

        }
        catch (SQLException e)
        {

            e.printStackTrace();

        }

    }

    // --- HELPERS DB ---

    private void inizializzaDatabase()
    {

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement())
        {

            stmt.execute("CREATE TABLE IF NOT EXISTS utenti (id TEXT PRIMARY KEY, punti INTEGER, errori INTEGER DEFAULT 0)");
            stmt.execute("CREATE TABLE IF NOT EXISTS config (chiave TEXT PRIMARY KEY, valore TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS blacklist (id TEXT PRIMARY KEY)");
            stmt.execute("INSERT OR IGNORE INTO config (chiave, valore) VALUES ('ultimo_numero', '0')");
            stmt.execute("INSERT OR IGNORE INTO config (chiave, valore) VALUES ('record_globale', '0')");

        }
        catch (SQLException e)
        {

            e.printStackTrace();

        }

    }

    private void caricaBlacklist()
    {

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT id FROM blacklist"))
        {

            while (rs.next()) blacklistCache.add(rs.getString("id"));

        }
        catch (SQLException e)
        {

            e.printStackTrace();

        }

    }

    private String caricaStringaGlobale(String chiave)
    {

        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT valore FROM config WHERE chiave = ?"))
        {

            ps.setString(1, chiave);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("valore");

        }
        catch (SQLException e)
        {

            e.printStackTrace();

        }

        return null;

    }

    private void salvaStringaGlobale(String chiave, String valore)
    {

        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement("INSERT INTO config(chiave, valore) VALUES(?, ?) ON CONFLICT(chiave) DO UPDATE SET valore = ?"))
        {

            ps.setString(1, chiave); ps.setString(2, valore); ps.setString(3, valore);
            ps.executeUpdate();

        }
        catch (SQLException e)
        {

            e.printStackTrace();

        }

    }

    private long caricaDatoGlobaleSingolo(String k)
    {

        String v = caricaStringaGlobale(k);
        return v != null ? Long.parseLong(v) : 0L;

    }

    private void salvaDatoGlobaleSingolo(String k, long v)
    {

        salvaStringaGlobale(k, String.valueOf(v));

    }

    private void aggiornaConfigurazione(Connection conn, String k, long v) throws SQLException
    {

        try (PreparedStatement ps = conn.prepareStatement("UPDATE config SET valore = ? WHERE chiave = ?"))
        {

            ps.setString(1, String.valueOf(v)); ps.setString(2, k); ps.executeUpdate();

        }

    }

    private long caricaDatoConfigurazione(Connection conn, String k) throws SQLException
    {

        try (PreparedStatement ps = conn.prepareStatement("SELECT valore FROM config WHERE chiave = ?"))
        {

            ps.setString(1, k);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Long.parseLong(rs.getString("valore")) : 0L;

        }

    }

    // --- METODI UTILITY (Stats, Top, ecc.) ---

    private void gestisciStats(SlashCommandInteractionEvent event)
    {

        String sql = "SELECT punti, errori FROM utenti WHERE id = ?";

        try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql))
        {

            pstmt.setString(1, event.getUser().getId());

            ResultSet rs = pstmt.executeQuery();

            int punti = 0;

            int errori = 0;

            if (rs.next()) {

                punti = rs.getInt("punti");

                errori = rs.getInt("errori");

            }

            EmbedBuilder eb = new EmbedBuilder()
                    .setTitle("Statistiche di " + event.getUser().getName())
                    .setColor(Color.GREEN)
                    .addField("Punti Attuali", "**" + punti + "**", true)
                    .addField("Reset Causati", "**" + errori + "**", true)
                    .setFooter("Fai attenzione a non sbagliare!");

            event.getHook().editOriginalEmbeds(eb.build()).queue();

        }
        catch (SQLException e)
        {

            e.printStackTrace();

        }

    }

    private void gestisciRank(SlashCommandInteractionEvent event)
    {

        String sql = "SELECT punti FROM utenti WHERE id = ?";

        try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql))
        {

            pstmt.setString(1, event.getUser().getId());

            ResultSet rs = pstmt.executeQuery();

            int punti = rs.next() ? rs.getInt("punti") : 0;

            int prossimoStep;
            String nomeGrado;

            if (punti >= 1000)
            {
                prossimoStep = -1;
                nomeGrado = "Imperatore dell'Infinito";
            }
            else if (punti >= 500)
            {
                prossimoStep = 1000;
                nomeGrado = "Architetto del Calcolo";
            }
            else if (punti >= 200)
            {
                prossimoStep = 500;
                nomeGrado = "Magnate delle Cifre";
            }
            else if (punti >= 50)
            {
                prossimoStep = 200;
                nomeGrado = "Esattore dei Numeri";
            }
            else if (punti >= 10)
            {
                prossimoStep = 50;
                nomeGrado = "Contabile alle Prime Armi";
            }
            else
            {
                prossimoStep = 10;
                nomeGrado = "Novizio";
            }

            String testoMancanti = (prossimoStep == -1)
                    ? "**Livello Massimo Raggiunto!**"
                    : "**" + (prossimoStep - punti) + "** punti al prossimo grado";

            EmbedBuilder eb = new EmbedBuilder()
                    .setTitle("Progresso Gradi di " + event.getUser().getName())
                    .setColor(Color.CYAN)
                    .addField("Grado Attuale", "**" + nomeGrado + "**", false)
                    .addField("Punti Totali", "**" + punti + "**", true)
                    .addField("Prossimo Obiettivo", testoMancanti, true)
                    .setFooter("Attenzione: se sbagli perdi 10 punti e il conteggio torna a 1!");

            event.getHook().editOriginalEmbeds(eb.build()).queue();

        }
        catch (SQLException e)
        {

            e.printStackTrace();

        }

    }

    private void gestisciTop(SlashCommandInteractionEvent event)
    {

        String sql = "SELECT id, punti FROM utenti ORDER BY punti DESC LIMIT 10";

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql))
        {

            EmbedBuilder eb = new EmbedBuilder()
                    .setTitle("Classifica Top 10")
                    .setColor(Color.YELLOW)
                    .setTimestamp(java.time.Instant.now());

            int pos = 1;

            while (rs.next())
            {

                eb.appendDescription(pos + ". <@" + rs.getString("id") + "> - **" + rs.getInt("punti") + "** pt\n");

                pos++;

            }

            event.getHook().editOriginalEmbeds(eb.build()).queue();

        }
        catch (SQLException e)
        {

            e.printStackTrace();

        }

    }

    private void gestisciRecord(SlashCommandInteractionEvent event)
    {

        long record = caricaDatoGlobaleSingolo("record_globale");

        event.getHook().editOriginal("Il **record assoluto** di questo server è: **" + record + "**!").queue();

    }

    private void gestisciTestoNonValido(MessageReceivedEvent event, String msg)
    {

        event.getMessage().delete().queue(null, e -> {});
        event.getChannel().sendMessage(event.getAuthor().getAsMention() + ", solo numeri!").queue(m -> m.delete().queueAfter(3, TimeUnit.SECONDS));
        inviaLogStaff(event, msg, ultimoNumero.get() + 1);

    }

    private void inviaLogStaff(MessageReceivedEvent event, String inviato, long atteso)
    {

        TextChannel logChannel = event.getGuild().getTextChannelById(idCanaleLog.get());
        if (logChannel == null) return;

        EmbedBuilder lb = new EmbedBuilder()
                .setTitle(inviato.matches("\\d+") ? "Errore Conteggio" : "Errore Formato")
                .setColor(inviato.matches("\\d+") ? Color.RED : Color.ORANGE)
                .addField("Utente", event.getAuthor().getAsTag(), true)
                .addField("Inviato", "`" + inviato + "`", true)
                .addField("Atteso", "`" + atteso + "`", true)
                .setTimestamp(java.time.Instant.now());

        logChannel.sendMessageEmbeds(lb.build()).queue();

    }

    private void controllaEAssegnaRuoli(MessageReceivedEvent event, int punti) {

        var m = event.getMember();
        if (m == null)
            return;

        String tid = null;
        if (punti >= 1000)
            tid = ID_RUOLI.get(4); //imperatore
        else if (punti >= 500)
            tid = ID_RUOLI.get(3); //architetto
        else if (punti >= 200)
            tid = ID_RUOLI.get(2); //magnate
        else if (punti >= 50)
            tid = ID_RUOLI.get(1); //esattore
        else if (punti >= 10)
            tid = ID_RUOLI.get(0); //contabile

        for (String rid : ID_RUOLI)
        {

            Role r = event.getGuild().getRoleById(rid);
            if (r == null)
                continue;

            if (rid.equals(tid))
            {

                if (!m.getRoles().contains(r))
                    event.getGuild().addRoleToMember(m, r).queue();

            }
            else
            {

                if (m.getRoles().contains(r))
                    event.getGuild().removeRoleFromMember(m, r).queue();

            }

        }

    }

}