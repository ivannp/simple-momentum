package net.tradelib.simplemomentum;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import net.tradelib.core.Bar;
import net.tradelib.core.BarHistory;
import net.tradelib.core.Calendar;
import net.tradelib.core.Instrument;
import net.tradelib.core.InstrumentVariation;
import net.tradelib.core.OrderNotification;
import net.tradelib.core.Position;
import net.tradelib.core.Strategy;
import net.tradelib.functors.Roc;

public class SimpleMomentum extends Strategy {
   
   private int lookback = 42;
   private int minLen = 300;
   private double initialEquity = 1_000_000;
   private int ntop = 4;
   private int nbottom = 0;
   
   int delay = 5;
   
   public int getLookback() {
      return lookback;
   }

   public void setLookback(int lookback) {
      this.lookback = lookback;
   }
   
   public int getMinLen() {
      return minLen;
   }

   public void setMinLen(int minLen) {
      this.minLen = minLen;
   }

   public double getInitialEquity() {
      return initialEquity;
   }

   public void setInitialEquity(double initialEquity) {
      this.initialEquity = initialEquity;
   }

   @Autowired
   Environment env;
   
   private LocalDate lastDate = LocalDate.MIN;
   
   private class Status {
      public Instrument instrument;
      public InstrumentVariation variation;
      
      public Roc roc                   = new Roc(lookback);
      public ArrayList<Double> returns = new ArrayList<Double>();
      
      public ArrayList<Double> longScores    = new ArrayList<Double>();
      public ArrayList<Double> shortScores   = new ArrayList<Double>();
      
      public long position             = 0;
      public long desiredPosition      = 0;
      public LocalDateTime since       = LocalDateTime.MIN;
      public double entryPrice         = 0.0;
      public double exitPrice          = 0.0;
      public double pnl                = 0.0;
      public double actualEntryPrice   = 0.0;
      public double currentExit        = 0.0;
      public double stopLoss           = Double.NaN;
      public double profitTarget       = Double.NaN;
      
      public BarHistory history;
      public double lastClose;
      public LocalDateTime ts;
      
      public double shortTermRoc       = 0;
      
      public Status(Instrument ii, InstrumentVariation iv) {
         instrument = ii;
         variation = iv;
      }
      
      public String getSymbol() { return instrument.getSymbol(); }
   }
   
   HashMap<String,Status> statuses_ = new HashMap<String, Status>();
   
   public void setSymbols(Iterable<String> symbols) throws Exception {
      for(String symbol : symbols) {
         Instrument instrument = broker.getInstrument(symbol);
         InstrumentVariation iv = broker.getInstrumentVariation("ib", instrument.getSymbol());
         statuses_.put(symbol, new Status(instrument, iv));
         subscribe(symbol);
      }
   }
   
   @Override
   protected void onNewDay(LocalDate prevDay, LocalDate newDay) throws Exception {
      if(!newDay.isAfter(getTradingStart().toLocalDate())) return;
      if(newDay.isAfter(getTradingStop().toLocalDate())) return;
      
      if(delay > 0) {
         --delay;
         return;
      }
      
      // Manage assets on first day of the month open
      if(prevDay.getMonth().equals(newDay.getMonth())) return;
      
      // Manage assets only on Monday's open
      // if(Calendar.toWeek(prevDay).equals(Calendar.toWeek(newDay))) return;
      
      // Sort the instruments
      Status [] longStatuses = statuses_.values().toArray(new Status[statuses_.size()]); 
      Arrays.sort(longStatuses, (s1, s2) -> -Double.compare(s1.longScores.get(0), s2.longScores.get(0)));
      
      // Reset
      statuses_.values().forEach((s) -> { s.desiredPosition = 0; });
      
      String longLine = "";
      String shortLine = "";
      
      int end = Math.min(ntop, longStatuses.length);
      for(int ii = 0; ii < end; ++ii) {
         Status status = longStatuses[ii];
         double roc = status.roc.last(); 
         String str = String.format("%s: %.2f", status.getSymbol(), roc);
         if(longLine.isEmpty()) longLine = str;
         else longLine += "; " + str;
         
         if(roc > 0) status.desiredPosition = 1;
      }
      
      if(!longLine.isEmpty()) {
         longLine = "Long: " + longLine + "; ";
      }
      
      // Cancel all active orders.
      broker.cancelAllOrders();
      
      // Save the end equity
      double endEquity = getAccount().getEndEquity();
      double positionSize = endEquity / end;
      
      for(Status ss : longStatuses) {
         long pos = 0;
         if(ss.position > 0) pos = 1;
         else if(ss.position < 0) pos = -1;
         if(pos != ss.desiredPosition) {
            if(pos == 0) {
               if(ss.desiredPosition > 0) {
                  long qty = (long)(positionSize / ss.lastClose); 
                  enterLong(ss.getSymbol(), qty);
               } else {
                  long qty = (long)(positionSize / ss.lastClose);
                  enterShort(ss.getSymbol(), qty);
               }
            } else if(pos > 0) {
               exitLong(ss.getSymbol());
               if(ss.desiredPosition < 0) {
                  long qty = (long)(positionSize / ss.lastClose);
                  enterShort(ss.getSymbol(), qty);
               }
            } else {
               exitShort(ss.getSymbol());
               if(ss.desiredPosition > 0) {
                  long qty = (long)(positionSize / ss.lastClose);
                  enterLong(ss.getSymbol(), qty);
               }
            }
         }
      }

      if(!shortLine.isEmpty()) {
         shortLine = "Short: " + shortLine + "; ";
      }
      
      if(!longLine.isEmpty() || !shortLine.isEmpty()) {
         System.out.println(String.format("%1$tY-%1$tm-%1$td [%1$ta]: %2$s%3$s", newDay, longLine, shortLine));
      }
   }
   
   @Override
   protected void onBarClosed(BarHistory history, Bar bar) throws Exception {
      
      double close = bar.getClose();
      
      if(close <= 0) {
         throw new RuntimeException(
                        String.format("Negative close for [%s %2$tY-%2$tm-%2$td]: %3$f",
                                      bar.getSymbol(), bar.getDateTime(), bar.getClose()));
      }
      
      Status status = statuses_.get(bar.getSymbol());
      status.history = history;
      status.lastClose = bar.getClose();
      status.ts = bar.getDateTime();
      // Feed the indicators
      double roc = status.roc.add(close);
      status.returns.add(roc);
      
      LocalDateTime dateTime = bar.getDateTime();
      
      status.position = broker.getPosition(status.instrument).quantity;
      
      if(dateTime.isAfter(getTradingStart())){
         
         status.longScores.clear();
         status.shortScores.clear();
         
         if(history.size() > minLen) {
            status.longScores.add(status.roc.last());
         }
         
         if(dateTime.isAfter(getTradingStop())) {
            Position pos = broker.getPosition(status.instrument);
            if(pos.quantity > 0) exitLong(bar.getSymbol());
            else if(pos.quantity < 0) exitShort(bar.getSymbol());
         }
      }
   }
   
   protected void onOrderNotification(OrderNotification on) {
      Status status = statuses_.get(on.order.getSymbol());
      if(on.order.isLongEntry()) {
         status.actualEntryPrice = on.execution.getPrice();
         status.stopLoss = Double.NaN;
         status.profitTarget = Double.NaN;
      } else if(on.order.isShortEntry()) {
         status.actualEntryPrice = on.execution.getPrice();
         status.stopLoss = Double.NaN;
         status.profitTarget = Double.NaN;
      }
   }
   
   protected void reportStatusAndAction(Status status) throws Exception {
      
      // Setup a Strategy.Status structure
      Strategy.Status sstatus = new Strategy.Status(status.getSymbol());
      persistStatus(sstatus);
   }
}
