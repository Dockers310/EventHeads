package ru.doksi.eventheads.events;

/** Collection requirements and environmental conditions for an event. */
public final class EventConditions {
    public boolean enabled = false;
    public String world = "";
    public String weather = "ANY";       // ANY/CLEAR/RAIN/THUNDER
    public String dayNight = "ANY";      // ANY/DAY/NIGHT
    public int timeMin = 0;
    public int timeMax = 24000;
    public String requiredInventoryMaterial = "";
    public String requiredHelmetMaterial = "";
    public String failMessage = "§cВы не можете собрать этот ивент: выполните требования.";
    public int failPenalty = 0;

    public EventConditions copy(){
        EventConditions c=new EventConditions();
        c.enabled=enabled; c.world=world; c.weather=weather; c.dayNight=dayNight;
        c.timeMin=timeMin; c.timeMax=timeMax;
        c.requiredInventoryMaterial=requiredInventoryMaterial; c.requiredHelmetMaterial=requiredHelmetMaterial;
        c.failMessage=failMessage; c.failPenalty=failPenalty;
        return c;
    }
}
