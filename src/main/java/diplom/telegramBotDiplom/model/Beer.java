package diplom.telegramBotDiplom.model;

import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
@Data
@Entity(name = "beerTable")
public class Beer  extends  Product{

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private int id;
    private String name;
    private int milliliters;
    private double price;

    public Beer() {
    }

    public Beer(int id, String name, int milliliters, double price) {
        this.id = id;
        this.name = name;
        this.milliliters = milliliters;
        this.price = price;
    }
}
