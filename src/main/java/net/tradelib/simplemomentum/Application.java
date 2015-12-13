package net.tradelib.simplemomentum;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.TreeMap;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMessage.RecipientType;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportResource;
import org.springframework.core.env.Environment;

import com.google.common.base.Splitter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.tradelib.core.HistoricalDataFeed;
import net.tradelib.core.HistoricalReplay;
import net.tradelib.misc.StrategyText;
import net.tradelib.misc.Utils;

@SpringBootApplication
@ImportResource("classpath:config.xml")
public class Application implements CommandLineRunner {
   
   @Autowired
   private SimpleMomentum strategy;
   
   @Autowired
   private Environment env;
   
   @Autowired
   private String dbUrl;
    
   @Autowired
   private Boolean emailEnabled = false;
   
   @Autowired
   private String emailUser;
   
   @Autowired
   private String emailPass;
   
   @Autowired
   private String emailFrom;
   
   @Autowired
   private String emailRecipients;
   
   public static void main(String[] args) {
      SpringApplication.run(Application.class, args);
   }

   @Override
   public void run(String... arg0) throws Exception {
      Utils.setupDefaultLogging();
      
      double initialEquity;
      String ss = env.getProperty("initial.equity");
      try {
         initialEquity = Double.parseDouble(ss);
      } catch(Exception ee) {
         initialEquity = 1_000_000;
      }
      
      strategy.cleanupDb();
      
      // Finish the strategy configuration
      ss = env.getProperty("trading.start");
      if(ss != null) {
         LocalDate ld = LocalDate.parse(ss, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
         strategy.setTradingStart(ld.atStartOfDay());
         // Start the account a couple of days before the trading starts
         ld = ld.minusDays(2);
         strategy.setInitialEquity(ld, initialEquity);
      }
      
      ss = env.getProperty("trading.stop");
      if(ss != null) {
         LocalDate ld = LocalDate.parse(ss, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
         strategy.setTradingStop(ld.atStartOfDay());
      }
      
      Iterable<String> symbols = Splitter.on(',')
                                    .trimResults()
                                    .omitEmptyStrings()
                                    .split(env.getProperty("symbols"));
      strategy.setSymbols(symbols);

      // Run the strategy
      long start = System.nanoTime();
      strategy.start();
      long elapsedTime = System.nanoTime() - start;
      System.out.println("backtest took " + String.format("%.2f secs",(double)elapsedTime/1e9));
      
      start = System.nanoTime();
      strategy.updateEndEquity();
      strategy.writeExecutionsAndTrades();
      strategy.writeEquity();
      elapsedTime = System.nanoTime() - start;
      System.out.println("writing to the database took " + String.format("%.2f secs",(double)elapsedTime/1e9));
      
      System.out.println();
      
      // Write the strategy totals to the database
      strategy.totalTradeStats();
      
      if(Boolean.parseBoolean(env.getProperty("write.report", "false"))) {
         // Write the strategy report to the database and obtain the JSON
         // for writing it to the console.
         JsonObject report = strategy.writeStrategyReport();
         
         JsonArray asa = report.getAsJsonArray("annual_stats");
         
         // If emails are being send out
         String signalText = StrategyText.build(
                  dbUrl,
                  strategy.getName(),
                  strategy.getLastTimestamp().toLocalDate(),
                  "\t");
         
         System.out.println(signalText);
         System.out.println();
         
         String message = "";
         
         if(asa.size() > 0) {
            // Sort the array
            TreeMap<Integer, Integer> map = new TreeMap<Integer, Integer>();
            for(int ii = 0; ii < asa.size(); ++ii) {
               int year = asa.get(ii).getAsJsonObject().get("year").getAsInt();
               map.put(year, ii);
            }
   
            for(int id : map.values()) {
               JsonObject jo = asa.get(id).getAsJsonObject();
               String yearStr = String.valueOf(jo.get("year").getAsInt());
               String pnlStr = String.format("$%,d", jo.get("pnl").getAsInt());
               String pnlPctStr = String.format("%.2f%%", jo.get("pnl_pct").getAsDouble());
               String endEqStr = String.format("$%,d", jo.get("end_equity").getAsInt());
               String ddStr = String.format("$%,d", jo.get("maxdd").getAsInt());
               String ddPctStr = String.format("%.2f%%", jo.get("maxdd_pct").getAsDouble());
               String str = yearStr + " PnL: " + pnlStr + ", PnL Pct: " + pnlPctStr +
                     ", End Equity: " + endEqStr + ", MaxDD: " + ddStr +
                     ", Pct MaxDD: " + ddPctStr;
               message += str + "\n";
            }
            
            String pnlStr = String.format("$%,d", report.get("pnl").getAsInt());
            String pnlPctStr = String.format("%.2f%%", report.get("pnl_pct").getAsDouble());
            String ddStr = String.format("$%,d", report.get("avgdd").getAsInt());
            String ddPctStr = String.format("%.2f%%", report.get("avgdd_pct").getAsDouble());
            String gainToPainStr = String.format("%.4f", report.get("gain_to_pain").getAsDouble());
            String str = "\nAvg PnL: " + pnlStr + ", Pct Avg PnL: " + pnlPctStr + 
                  ", Avg DD: " + ddStr + ", Pct Avg DD: " + ddPctStr + 
                  ", Gain to Pain: " + gainToPainStr;
            message += str + "\n";
         } else {
            message += "\n";
         }
         
         // Global statistics
         JsonObject jo = report.getAsJsonObject("total_peak");
         String dateStr = jo.get("date").getAsString();
         int maxEndEq = jo.get("equity").getAsInt();
         jo = report.getAsJsonObject("total_maxdd");
         double cash = jo.get("cash").getAsDouble();
         double pct = jo.get("pct").getAsDouble();
         message += 
                  "\n" +
                  "Total equity peak [" + dateStr + "]: " + String.format("$%,d", maxEndEq) +
                  "\n" +
                  String.format("Current Drawdown: $%,d [%.2f%%]", Math.round(cash), pct) +
                  "\n";
         
         if(report.has("latest_peak") && report.has("latest_maxdd")) {
            jo = report.getAsJsonObject("latest_peak");
            LocalDate ld = LocalDate.parse(jo.get("date").getAsString(), DateTimeFormatter.ISO_DATE);
            maxEndEq = jo.get("equity").getAsInt();
            jo = report.getAsJsonObject("latest_maxdd");
            cash = jo.get("cash").getAsDouble();
            pct = jo.get("pct").getAsDouble();
            message += 
                  "\n" +
                  Integer.toString(ld.getYear()) + " equity peak [" + 
                  ld.format(DateTimeFormatter.ISO_DATE) + "]: " + String.format("$%,d", maxEndEq) +
                  "\n" +
                  String.format("Current Drawdown: $%,d [%.2f%%]", Math.round(cash), pct) +
                  "\n";
         }
         
         message += "\n" +
                    "Avg Trade PnL: " + String.format("$%,d", Math.round(report.get("avg_trade_pnl").getAsDouble())) +
                    ", Max DD: " + String.format("$%,d", Math.round(report.get("maxdd").getAsDouble())) +
                    ", Max DD Pct: " + String.format("%.2f%%", report.get("maxdd_pct").getAsDouble()) +
                    ", Num Trades: " + Integer.toString(report.get("num_trades").getAsInt());
         
         System.out.println(message);
         
         if(emailEnabled) {
            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host", "smtp.sendgrid.net");
            props.put("mail.smtp.port", "587");
            
            Session session = Session.getInstance(
                              props,
                              new javax.mail.Authenticator() {
                                 protected PasswordAuthentication getPasswordAuthentication() {
                                    return new PasswordAuthentication(emailUser, emailPass);
                                 }
                              });
            
            MimeMessage msg = new MimeMessage(session);
            try {
               msg.setFrom(new InternetAddress(emailFrom));
               msg.addRecipients(RecipientType.TO, emailRecipients);
               msg.setSubject(strategy.getName() + " Report [" + strategy.getLastTimestamp().format(DateTimeFormatter.ISO_LOCAL_DATE) + "]");
               msg.setText("Positions & Signals\n" + signalText + "\n\nStatistics\n" + message);
               Transport.send(msg);
            } catch (Exception ee) {
               Logger.getLogger("").warning(ee.getMessage());
            }
         }
      }
   }
}
