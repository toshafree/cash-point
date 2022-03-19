
var app = new Vue({
    el: '#app',
    data: {
        cashPoints: []
    },
    created(){
        let socket = new WebSocket("ws://localhost:9000/ws")
        socket.onmessage = (event) => {
            let statePack = JSON.parse(event.data.replaceAll("\\\"","\\\""))
            switch (statePack.state) {
                case "filledUp":
                    this.cashPoints.push(statePack.data)
                    break
                case "change":
                    this.$set(this.cashPoints, this.cashPoints.findIndex((p) => p.id == statePack.data.id), statePack.data)
                    break
                case "getEmpty":
                    this.$delete(this.cashPoints, this.cashPoints.findIndex((p) => p.id == statePack.data.id))
                    break
                case "new":
                    this.cashPoints.push(statePack.data)
                    break
            }
        }

    }
})

Vue.component('cash-point', {
    props: ['data'],
    data: function () {
        return {
            dollars: 0
        }
    },
    computed: {
        animatedDollars: function() {
            return this.dollars.toFixed(0);
        }
    },
    mounted () {
        this.dollars = this.data.dollars
    },
    watch: {
        data: function(newValue) {
            gsap.to(this.$data, { duration: 2, dollars: newValue.dollars });
        }
    },
    template: '<li>{{ data.id }} | {{ animatedDollars }} | {{ data.address }}</li>'

})