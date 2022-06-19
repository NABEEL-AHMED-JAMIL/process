package process.util;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 * class use to render the hh:mm from 24 hrs
 */
public class TimeOfDay {

    private int hour;
    private int minute;

    public TimeOfDay() {}

    public TimeOfDay(int hour, int minute) {
        this.hour = hour;
        this.minute = minute;
    }

    public int getHour() {
        return hour;
    }

    public void setHour(int hour) {
        this.hour = hour;
    }

    public int getMinute() {
        return minute;
    }

    public void setMinute(int minute) {
        this.minute = minute;
    }

    public void setHr(int hour)
    {
        if (0 <= hour && hour < 23 ) {
            this.hour = hour;
        } else {
            this.hour = 0;
        }
     }

    public void setMin (int minute)
    {
        if (0 <= minute && minute < 59) {
            this.minute = minute;
        } else {
            this.minute = 0;
        }
    }

    public void addHour()
    {
        hour++;
        if (hour > 23) {
            hour = 0;
        }
    }

    public void addMinute()
    {
        minute++;
        if (minute > 59)
        {
            minute = 0;
            addHour(); //increment hour
        }
    }

    //static int rowSize = 0;
    public void printCurrentTime() {
        this.hour = getHour();
        this.minute = getMinute();
        /**
         * comment condition use to compress the output
         * if (rowSize == 6) {
         *     rowSize = 0;
         *     System.out.print((this.hour > 9 ? "\'"+this.hour: "\'0"+this.hour) +":"+
         *        (this.minute > 9 ? ""+this.minute: "0"+this.minute)+"\',\n");
         * } else {
         *     rowSize ++;
         *     System.out.print((this.hour > 9 ? "\'"+this.hour: "\'0"+this.hour) +":"+
         *       (this.minute > 9 ? ""+this.minute: "0"+this.minute)+"\',");
         * }
         */
        System.out.print((this.hour > 9 ? "\'"+this.hour: "\'0"+this.hour) +":"+
            (this.minute > 9 ? ""+this.minute: "0"+this.minute)+"\',");
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

    /**
     * public static void main(String[] args) {
     *   TimeOfDay timeOfDay = new TimeOfDay(00, 00);
     *   for (int i=0; i<=1439; i++) {
     *      timeOfDay.printCurrentTime();
     *      timeOfDay.addMinute();
     *   }
     * }*/
}
