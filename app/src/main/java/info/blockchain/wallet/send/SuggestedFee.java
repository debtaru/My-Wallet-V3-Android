package info.blockchain.wallet.send;

import java.math.BigInteger;
import java.util.ArrayList;

public class SuggestedFee {

    public BigInteger defaultFeePerKb;
    public ArrayList<Estimates> estimateList;
    public boolean isSurge;

    @Override
    public String toString() {
        return "SuggestedFee{" +
                "defaultFeePerKb=" + defaultFeePerKb +
                ", estimateList=" + estimateList +
                '}';
    }

    public static class Estimates{

        public Estimates(BigInteger fee, boolean surge, boolean ok) {
            this.fee = fee;
            this.surge = surge;
            this.ok = ok;
        }

        public BigInteger fee;
        public boolean surge;
        public boolean ok;
    }
}
