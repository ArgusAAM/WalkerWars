package walker;

import arc.*;
import arc.struct.*;
import arc.math.*;
import mindustry.ai.types.*;
import mindustry.graphics.Layer;
import mindustry.entities.abilities.*;
import mindustry.content.*;
import mindustry.entities.bullet.*;
import mindustry.entities.pattern.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.type.*;
import mindustry.ui.dialogs.*;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.units.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mindustry.world.meta.BuildVisibility;

import static mindustry.Vars.*;

public class WalkerMod extends Mod{
    static final float FOREVER = 60f * 60f * 60f * 24f;
    static final String[] CH = {"Лёгкий «Страйдер»", "Средний «Вардэн»", "Тяжёлый «Колосс»", "Шахтёр «Копатель»", "Шахтёр «Бурмастер»", "Летающий «Икар»", "Паук «Ткач»"};
    static final String[] INFO = {
        "Ходьба · пульсовые болты · поле ускорения союзников",
        "Ходьба · ракетный блок · ремонтное поле",
        "Ходьба · осадная пушка · силовой щит",
        "Ходьба + добыча до угля · огнебур · броня-плита",
        "Ходьба + добыча титана · бур-огнемёт · усиленный каркас",
        "Прыжки над препятствиями · игольная пушка · регенерация щита",
        "Шесть ног, ходит по любому рельефу · дуговой разрядник · энергополе"};
    static final int[][] CH_COST = {{80, 40, 0}, {200, 120, 40}, {450, 300, 150}, {120, 60, 0}, {320, 200, 80}, {150, 100, 20}, {260, 160, 60}};
    static final String[] WP = {"Автопушка", "Дробовик", "Ракеты", "Артиллерия"};
    static final int[][] WP_COST = {{30, 10, 0}, {40, 20, 0}, {50, 30, 20}, {70, 40, 40}};
    static final String[] HU = {"Стандартный корпус", "Бронеплиты", "Лёгкий каркас", "Серво-привод"};
    static final int[][] HU_COST = {{0, 0, 0}, {60, 20, 10}, {30, 30, 0}, {40, 40, 20}};
    static final String[] DE = {"Нет", "Ремонт-модуль", "Разгон орудий", "Усилитель урона"};
    static final int[][] DE_COST = {{0, 0, 0}, {40, 40, 20}, {50, 30, 30}, {60, 20, 40}};
    static final String[] PI = {"Штурмовик (+урон)", "Инженер (саморемонт)", "Разведчик (+скорость)"};

    static WalkerType[] chassis = new WalkerType[7];
    static StatusEffect marker;
    static StatusEffect[] hull = new StatusEffect[4], device = new StatusEffect[4], pilot = new StatusEffect[3];
    static Loadout mine = new Loadout();
    static BaseDialog hangar;
    static int tick;

    /** Оружие с идентификатором слота/стороны, чтобы выбирать нужные при сборке. */
    static class LW extends Weapon{
        final String id;
        LW(String id, String sprite){ super(sprite); this.id = id; }
    }

    /** Шагоход со своими спрайтами (assets/sprites/units). Шахтёры умеют добывать руду. */
    static class WalkerType extends UnitType{
        final boolean miner;
        WalkerType(String id, String title, float health, float speed, float hit, float scale, int mineTier, float mineSpeed, int move){
            super(id);
            constructor = MechUnit::create;
            localizedName = title;
            this.health = health; this.speed = speed; this.hitSize = hit;
            this.miner = mineTier >= 0;
            if(miner){ this.mineTier = mineTier; this.mineSpeed = mineSpeed; this.mineWalls = false; this.aiController = MinerAI::new; }
            if(move == 1){ canBoost = true; boostMultiplier = 2f; }
            if(move == 2){
                constructor = LegsUnit::create;
                legCount = 6; legLength = 10f; legForwardScl = 0.6f; legMoveSpace = 1.4f;
                hovering = true; armor = 3f; drag = 0.4f; rotateSpeed = 3f;
                shadowElevation = 0.2f; groundLayer = Layer.legUnit - 1f;
            }
            weapons.add(signature(id, scale));
            for(int slot = 0; slot < 2; slot++) for(int i = 0; i < WP.length; i++) for(int side = 0; side < 2; side++) weapons.add(makeWeapon(slot, i, side, scale));
        }
    }

    /** Уникальное оружие конкретного шагохода: нельзя снять, свой спрайт и свой тип снарядов. */
    static Weapon signature(String id, float scale){
        LW w = new LW("S" + id, "walker-wars-" + switch(id){
            case "striker" -> "w-pulse"; case "warden" -> "w-rocketpod"; case "colossus" -> "w-siegecannon";
            case "digger" -> "w-drillgun"; case "boremaster" -> "w-boredrill"; case "icarus" -> "w-needle"; default -> "w-arc";
        });
        w.mirror = false; w.alternate = false; w.rotate = false; w.top = true; w.x = 0f; w.y = 4f * scale;
        switch(id){
            case "striker" -> { w.reload = 9f; w.bullet = new LaserBoltBulletType(5.2f, 7){{ lifetime = 34f; }}; }
            case "warden" -> { w.reload = 55f; w.shoot = new ShootSpread(3, 5f); w.bullet = new MissileBulletType(3f, 10){{ splashDamage = 18f; splashDamageRadius = 22f; homingPower = 0.1f; lifetime = 60f; }}; }
            case "colossus" -> { w.reload = 120f; w.y = 6f * scale; w.bullet = new ArtilleryBulletType(2.4f, 30){{ splashDamage = 110f; splashDamageRadius = 56f; lifetime = 100f; knockback = 1.2f; }}; }
            case "digger" -> { w.reload = 10f; w.bullet = new BasicBulletType(2.2f, 14){{ lifetime = 22f; pierce = true; pierceCap = 3; status = StatusEffects.burning; statusDuration = 180f; hittable = false; }}; }
            case "boremaster" -> { w.reload = 14f; w.bullet = new BasicBulletType(2.4f, 26){{ lifetime = 26f; pierce = true; pierceCap = 4; status = StatusEffects.burning; statusDuration = 240f; hittable = false; }}; }
            case "icarus" -> { w.reload = 22f; w.bullet = new BasicBulletType(7f, 18){{ lifetime = 30f; pierce = true; pierceCap = 2; width = 4f; height = 14f; }}; }
            default -> { w.reload = 30f; w.bullet = new LightningBulletType(){{ damage = 18f; lightningLength = 14; collidesAir = false; }}; }
        }
        return w;
    }

    static Weapon makeWeapon(int slot, int i, int side, float scale){
        String[] sprites = {"walker-wars-w-autocannon", "walker-wars-w-shotgun", "walker-wars-w-missiles", "walker-wars-w-artillery"};
        LW w = new LW((slot == 0 ? "A" : "B") + i + (side == 0 ? "L" : "R"), sprites[i]);
        w.mirror = false; w.alternate = false; w.rotate = false; w.top = false;
        w.x = (side == 0 ? -1 : 1) * (slot == 0 ? 3.5f : 6.5f) * scale;
        w.y = slot == 0 ? 1.5f : -1.5f;
        w.flipSprite = side == 0;
        switch(i){
            case 0 -> { w.reload = 14f; w.bullet = new BasicBulletType(2.8f, 9){{ lifetime = 60f; width = 7f; height = 9f; }}; }
            case 1 -> { w.reload = 38f; w.shoot = new ShootSpread(4, 7f); w.bullet = new BasicBulletType(3f, 7){{ lifetime = 26f; }}; }
            case 2 -> { w.reload = 42f; w.bullet = new MissileBulletType(2.7f, 12){{ splashDamage = 24f; splashDamageRadius = 26f; homingPower = 0.08f; lifetime = 65f; }}; }
            default -> { w.reload = 75f; w.bullet = new ArtilleryBulletType(2f, 20){{ splashDamage = 65f; splashDamageRadius = 42f; lifetime = 95f; knockback = 0.8f; }}; }
        }
        return w;
    }

    static StatusEffect fx(String n, float hp, float spd, float dmg, float rel, float regen){
        StatusEffect s = new StatusEffect(n);
        s.permanent = true; s.show = false;
        s.healthMultiplier = hp; s.speedMultiplier = spd; s.damageMultiplier = dmg; s.reloadMultiplier = rel; s.damage = -regen;
        return s;
    }

    /** Полная сборка: шасси, 2 слота оружия, корпус, устройство, пилот. */
    static class Loadout{
        int ch, wa, wb = 2, hu, de, pi;

        ItemStack[] cost(){
            int[] t = new int[3];
            for(int[] c : new int[][]{CH_COST[ch], WP_COST[wa], WP_COST[wb], HU_COST[hu], DE_COST[de]}) for(int k = 0; k < 3; k++) t[k] += c[k];
            return ItemStack.with(Items.copper, t[0], Items.lead, t[1], Items.graphite, t[2]);
        }

        void apply(Unit u){
            Seq<WeaponMount> keep = new Seq<>();
            for(WeaponMount m : u.mounts) if(m.weapon instanceof LW w && (w.id.startsWith("S") || w.id.startsWith("A" + wa) || w.id.startsWith("B" + wb))) keep.add(m);
            u.mounts = keep.toArray(WeaponMount.class);
            u.apply(hull[hu], FOREVER); u.apply(device[de], FOREVER); u.apply(pilot[pi], FOREVER); u.apply(marker, FOREVER);
        }

        static Loadout random(int chassisIndex){
            Loadout l = new Loadout();
            l.ch = chassisIndex; l.wa = Mathf.random(3); l.wb = Mathf.random(3);
            l.hu = Mathf.random(3); l.de = Mathf.random(3); l.pi = Mathf.random(2);
            return l;
        }
    }

    @Override
    public void loadContent(){
        marker = fx("walker-configured", 1f, 1f, 1f, 1f, 0f);
        hull[0] = fx("hull-std", 1f, 1f, 1f, 1f, 0f);
        hull[1] = fx("hull-armor", 1.6f, 0.85f, 1f, 1f, 0f);
        hull[2] = fx("hull-light", 0.8f, 1.25f, 1f, 1f, 0f);
        hull[3] = fx("hull-servo", 1f, 1.35f, 1f, 1f, 0f);
        device[0] = fx("dev-none", 1f, 1f, 1f, 1f, 0f);
        device[1] = fx("dev-repair", 1f, 1f, 1f, 1f, 0.05f);
        device[2] = fx("dev-overclock", 1f, 1f, 1f, 1.4f, 0f);
        device[3] = fx("dev-amp", 1f, 1f, 1.35f, 1f, 0f);
        pilot[0] = fx("pilot-assault", 1f, 1f, 1.15f, 1f, 0f);
        pilot[1] = fx("pilot-engineer", 1f, 1f, 1f, 1f, 0.03f);
        pilot[2] = fx("pilot-scout", 1f, 1.15f, 1f, 1f, 0f);

        chassis[0] = new WalkerType("striker", "Страйдер", 220f, 0.6f, 9f, 1f, -1, 0f, 0);
        chassis[1] = new WalkerType("warden", "Вардэн", 560f, 0.43f, 12f, 1.3f, -1, 0f, 0);
        chassis[2] = new WalkerType("colossus", "Колосс", 1400f, 0.33f, 16f, 1.6f, -1, 0f, 0);
        chassis[3] = new WalkerType("digger", "Копатель", 380f, 0.45f, 10f, 1.1f, 2, 0.9f, 0);
        chassis[4] = new WalkerType("boremaster", "Бурмастер", 1000f, 0.36f, 15f, 1.5f, 3, 1.6f, 0);
        chassis[5] = new WalkerType("icarus", "Икар", 260f, 0.55f, 9f, 1.1f, -1, 0f, 1);
        chassis[6] = new WalkerType("weaver", "Ткач", 700f, 0.5f, 14f, 1.4f, -1, 0f, 2);
        // Встроенные устройства: у каждого шагохода своё.
        chassis[0].abilities.add(new StatusFieldAbility(StatusEffects.overclock, 240f, 300f, 70f));
        chassis[1].abilities.add(new RepairFieldAbility(12f, 180f, 80f));
        chassis[2].abilities.add(new ForceFieldAbility(60f, 0.3f, 400f, 360f));
        chassis[3].abilities.add(new RepairFieldAbility(6f, 300f, 50f));
        chassis[4].abilities.add(new ForceFieldAbility(45f, 0.2f, 250f, 480f));
        chassis[5].abilities.add(new ShieldRegenFieldAbility(15f, 60f, 120f, 80f));
        chassis[6].abilities.add(new EnergyFieldAbility(20f, 65f, 90f));
    }

    @Override
    public void init(){
        // Добывать могут только шахтёры-шагоходы: гасим добычу у всех остальных юнитов и прячем буры.
        content.units().each(u -> { if(!(u instanceof WalkerType)){ u.mineTier = -1; u.mineSpeed = 0f; } });
        // Ванильных юнитов в игре нет: ядра выдают шагоходов, фабрики юнитов скрыты.
        ((CoreBlock)Blocks.coreShard).unitType = chassis[0];
        ((CoreBlock)Blocks.coreFoundation).unitType = chassis[5];
        ((CoreBlock)Blocks.coreNucleus).unitType = chassis[1];
        content.blocks().each(b -> b instanceof UnitFactory || b instanceof Reconstructor, b -> b.buildVisibility = BuildVisibility.hidden);
        content.blocks().each(b -> b instanceof Drill, b -> b.buildVisibility = BuildVisibility.hidden);

        Events.on(WorldLoadEvent.class, e -> {
            Seq<SpawnGroup> g = new Seq<>();
            g.add(new SpawnGroup(chassis[0]){{ begin = 1; unitScaling = 1.6f; max = 16; }});
            g.add(new SpawnGroup(chassis[1]){{ begin = 8; spacing = 2; unitScaling = 2.2f; max = 10; shieldScaling = 20f; }});
            g.add(new SpawnGroup(chassis[2]){{ begin = 20; spacing = 3; unitScaling = 3f; max = 6; shieldScaling = 40f; }});
            g.add(new SpawnGroup(chassis[6]){{ begin = 5; spacing = 2; unitScaling = 2.5f; max = 8; }});
            g.add(new SpawnGroup(chassis[5]){{ begin = 14; spacing = 3; unitScaling = 3f; max = 6; shieldScaling = 25f; }});
            state.rules.spawns = g;
            content.blocks().each(b -> b instanceof UnitFactory || b instanceof Reconstructor, b -> state.rules.bannedBlocks.add(b));
            content.blocks().each(b -> b instanceof Drill, b -> state.rules.bannedBlocks.add(b));
            Seq<Building> drills = new Seq<>();
            Groups.build.each(b -> { if(b.block instanceof Drill) drills.add(b); });
            drills.each(Building::kill);
            CoreBuild c0 = state.rules.defaultTeam.core();
            if(c0 != null && state.wave <= 1){ c0.items.add(Items.copper, 500); c0.items.add(Items.lead, 300); c0.items.add(Items.graphite, 80); }
        });

        // Небольшие трофеи с врагов; основной ресурс дают только шахтёры.
        Events.on(UnitDestroyEvent.class, e -> {
            CoreBuild c = state.rules.defaultTeam.core();
            if(c == null || e.unit.team != state.rules.waveTeam) return;
            int n = (int)(e.unit.type.health / 16f);
            c.items.add(Items.copper, n); c.items.add(Items.lead, n / 2); c.items.add(Items.graphite, n / 8);
        });
        // Вражеские шагоходы получают случайную сборку.
        Events.run(Trigger.update, () -> {
            if(!state.isGame() || ++tick % 30 != 0) return;
            Groups.unit.each(u -> {
                if(!(u.type instanceof WalkerType)){ u.kill(); return; }
                if(u.team == state.rules.waveTeam && u.type instanceof WalkerType wt && !u.hasEffect(marker)){
                    Loadout.random(java.util.Arrays.asList(chassis).indexOf(wt)).apply(u);
                }
            });
        });

        Events.on(ClientLoadEvent.class, e -> {
            buildHangar();
            ui.hudGroup.fill(t -> {
                t.top().left().margin(180f, 4f, 0f, 0f);
                t.button("Ангар", () -> hangar.show()).size(110f, 50f);
            });
        });
    }

    static void buildHangar(){
        hangar = new BaseDialog("Ангар шагохода");
        hangar.addCloseButton();
        Runnable[] rebuild = {null};
        rebuild[0] = () -> {
            hangar.cont.clear();
            row("Шасси", CH[mine.ch], () -> { mine.ch = (mine.ch + 1) % CH.length; rebuild[0].run(); });
            row("Оружие A", WP[mine.wa], () -> { mine.wa = (mine.wa + 1) % WP.length; rebuild[0].run(); });
            row("Оружие B", WP[mine.wb], () -> { mine.wb = (mine.wb + 1) % WP.length; rebuild[0].run(); });
            row("Корпус", HU[mine.hu], () -> { mine.hu = (mine.hu + 1) % HU.length; rebuild[0].run(); });
            row("Устройство", DE[mine.de], () -> { mine.de = (mine.de + 1) % DE.length; rebuild[0].run(); });
            row("Солдат", PI[mine.pi], () -> { mine.pi = (mine.pi + 1) % PI.length; rebuild[0].run(); });
            hangar.cont.add(INFO[mine.ch]).colspan(2).pad(6f).row();
            StringBuilder sb = new StringBuilder("Цена: ");
            for(ItemStack s : mine.cost()) sb.append(s.item.localizedName).append(' ').append(s.amount).append("  ");
            hangar.cont.add(sb.toString()).colspan(2).pad(8f).row();
            hangar.cont.button("Сесть в шагоход", () -> deploy(true)).size(220f, 50f).pad(4f);
            hangar.cont.button("Создать союзника", () -> deploy(false)).size(220f, 50f).pad(4f);
        };
        hangar.shown(rebuild[0]);
    }

    static void row(String label, String value, Runnable next){
        hangar.cont.add(label).left().pad(4f);
        hangar.cont.button(value, next).size(260f, 44f).pad(4f).row();
    }

    static void deploy(boolean control){
        CoreBuild c = player.team().core();
        if(c == null) return;
        ItemStack[] cost = mine.cost();
        if(!c.items.has(cost)){ ui.showInfoToast("Не хватает ресурсов", 2f); return; }
        c.items.remove(cost);
        Unit u = chassis[mine.ch].create(player.team());
        u.set(c.x, c.y + c.block.size * 4f + 12f);
        mine.apply(u);
        u.add();
        if(control) Call.unitControl(player, u);
        hangar.hide();
    }
}
